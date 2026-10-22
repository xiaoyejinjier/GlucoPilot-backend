#!/usr/bin/env python3
"""Host-only resource collector. No credentials, network listener, or shell commands."""
import argparse
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

PROJECTS = ('glucopilot', 'glucopilot-storage')


def cpu_sample(text, previous):
    lines = text.splitlines()
    ticks = [int(v) for v in lines[0].split()[1:9]]
    if len(ticks) < 4 or any(v < 0 for v in ticks):
        raise ValueError('Invalid CPU counters')
    state = {'total': sum(ticks), 'idle': ticks[3] + (ticks[4] if len(ticks) > 4 else 0)}
    percent = None
    if previous:
        total = state['total'] - previous.get('total', state['total'])
        idle = state['idle'] - previous.get('idle', state['idle'])
        if total > 0 and 0 <= idle <= total:
            percent = round(100 * (total - idle) / total, 2)
    cores = sum(1 for line in lines if re.match(r'^cpu\d+\s', line))
    return percent, max(1, cores), state


def memory_sample(text):
    values = {}
    for line in text.splitlines():
        key, value = line.split(':', 1)
        values[key] = int(value.split()[0]) * 1024
    total, available = values['MemTotal'], values['MemAvailable']
    if total <= 0 or not 0 <= available <= total:
        raise ValueError('Invalid memory counters')
    used = total - available
    swap_total, swap_free = values.get('SwapTotal', 0), values.get('SwapFree', 0)
    if not 0 <= swap_free <= swap_total:
        raise ValueError('Invalid swap counters')
    return dict(memoryTotalBytes=total, memoryAvailableBytes=available,
                memoryUsedBytes=used, memoryPercent=round(used / total * 100, 2),
                swapTotalBytes=swap_total, swapUsedBytes=swap_total - swap_free)


def size_bytes(value):
    match = re.fullmatch(r'\s*([\d.]+)\s*(B|[KMGTPE]i?B)\s*', value)
    if not match:
        raise ValueError('Invalid Docker memory value')
    number, unit = match.groups()
    exponent = 0 if unit == 'B' else 'KMGTPE'.index(unit[0]) + 1
    result = float(number) * ((1024 if 'i' in unit else 1000) ** exponent)
    if not math.isfinite(result) or result < 0:
        raise ValueError('Invalid Docker memory value')
    return round(result)


def docker(*args):
    result = subprocess.run(['docker', *args], capture_output=True, text=True, timeout=6)
    if result.returncode:
        raise RuntimeError('Docker collection unavailable')
    return result.stdout


def containers_sample(run=docker):
    ids = []
    for project in PROJECTS:
        ids.extend(run('ps', '-a', '--filter', 'label=com.docker.compose.project=' + project,
                       '--format', '{{.ID}}').split())
    ids = list(dict.fromkeys(ids))
    if len(ids) > 30 or any(not re.fullmatch('[a-f0-9]{12,64}', v) for v in ids):
        raise ValueError('Unexpected project container inventory')
    if not ids:
        return []
    template = ('{"id":{{json .Id}},"name":{{json .Name}},'
                '"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
                '"state":{{json .State.Status}},"limit":{{.HostConfig.Memory}}}')
    inventory = [json.loads(line) for line in run('inspect', '--format', template, *ids).splitlines()]
    running = [item['id'] for item in inventory if item['state'] == 'running']
    stats = []
    if running:
        stats = [json.loads(line) for line in run('stats', '--no-stream', '--format', '{{json .}}',
                                                *running).splitlines()]
    rows = []
    for item in inventory:
        stat = next((s for s in stats if item['id'].startswith(s['ID'])), None)
        cpu, used = None, None
        if stat:
            cpu = float(stat['CPUPerc'].rstrip('%'))
            if not math.isfinite(cpu) or cpu < 0:
                raise ValueError('Invalid Docker CPU counter')
            used = size_bytes(stat['MemUsage'].split('/')[0])
        rows.append(dict(name=item['name'].lstrip('/'), service=item['service'] or '',
                         state=item['state'], cpuPercent=cpu, memoryUsedBytes=used,
                         memoryLimitBytes=item['limit'] or None))
    return sorted(rows, key=lambda row: row['name'])


def host_sample(proc, previous, disk):
    cpu, cores, state = cpu_sample((proc / 'stat').read_text(), previous)
    memory = memory_sample((proc / 'meminfo').read_text())
    uptime = float((proc / 'uptime').read_text().split()[0])
    if not math.isfinite(uptime) or uptime < 0 or disk.total <= 0:
        raise ValueError('Invalid host counters')
    return dict(cpuPercent=cpu, cpuCores=cores, **memory, diskTotalBytes=disk.total,
                diskUsedBytes=disk.used, diskFreeBytes=disk.free,
                diskPercent=round(disk.used / disk.total * 100, 2), uptimeSeconds=int(uptime)), state


def history_sample(previous, host, sampled_at):
    history = [row for row in previous if isinstance(row, dict)
               and isinstance(row.get('sampledAt'), str) and row['sampledAt'] < sampled_at]
    history = sorted(history, key=lambda row: row['sampledAt'])[-59:]
    return history + [dict(sampledAt=sampled_at, cpuPercent=host['cpuPercent'],
                           memoryPercent=host['memoryPercent'])]


def read_json(file, default):
    try:
        if file.stat().st_size > 1024 * 1024:
            return default
        return json.loads(file.read_text())
    except (OSError, ValueError):
        return default


def atomic_json(file, value, mode):
    fd, name = tempfile.mkstemp(prefix='.snapshot-', dir=file.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=True, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, mode)
        os.replace(name, file)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def collect(root):
    public, private = root / 'public', root / 'private'
    public.mkdir(mode=0o755, parents=True, exist_ok=True)
    private.mkdir(mode=0o700, parents=True, exist_ok=True)
    state = read_json(private / 'cpu.json', {})
    old = read_json(public / 'metrics.json', {})
    host, state = host_sample(Path('/proc'), state, shutil.disk_usage('/'))
    sampled_at = datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')
    available = True
    try:
        containers = containers_sample()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, KeyError):
        containers, available = [], False
    data = dict(schemaVersion=1, sampledAt=sampled_at, host=host,
                containersAvailable=available, containers=containers,
                history=history_sample(old.get('history', []), host, sampled_at))
    atomic_json(private / 'cpu.json', state, 0o600)
    atomic_json(public / 'metrics.json', data, 0o644)


if __name__ == '__main__':
    import fcntl
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('/var/lib/glucopilot-monitor'))
    args = parser.parse_args()
    args.root.mkdir(mode=0o755, parents=True, exist_ok=True)
    with (args.root / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        collect(args.root)
