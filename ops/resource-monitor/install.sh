#!/bin/bash
set -euo pipefail
test "$(id -u)" = 0
command -v python3 >/dev/null
command -v docker >/dev/null
base=$(cd -- "$(dirname -- "$0")" && pwd)
install -d -m 755 /opt/glucopilot-monitor /var/lib/glucopilot-monitor/public
install -d -m 700 /var/lib/glucopilot-monitor/private
install -m 755 "$base/collector.py" /opt/glucopilot-monitor/collector.py
cat > /etc/systemd/system/glucopilot-monitor.service <<'EOF'
[Unit]
Description=GlucoPilot host and project-container resource snapshot
After=docker.service

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /opt/glucopilot-monitor/collector.py
TimeoutStartSec=25
UMask=0022
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/glucopilot-monitor
EOF
cat > /etc/systemd/system/glucopilot-monitor.timer <<'EOF'
[Unit]
Description=Collect GlucoPilot resource metrics every 10 seconds

[Timer]
OnBootSec=20
OnUnitActiveSec=10
AccuracySec=1
Unit=glucopilot-monitor.service

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now glucopilot-monitor.timer
systemctl start glucopilot-monitor.service
systemctl is-active glucopilot-monitor.timer
echo 'Collector installed. Mount /var/lib/glucopilot-monitor/public read-only at /app/monitor in the API container.'
