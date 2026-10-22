# 服务器资源监控

宿主机采集器每10秒写入一个JSON快照，保留最近60次CPU和内存采样。只查询本项目 `glucopilot` / `glucopilot-storage` 两个Compose项目，不读取容器环境变量、进程命令或数据库内容，不提供HTTP监听端口。

## 服务器安装

将本目录上传服务器，在该目录执行：

```bash
sudo bash install.sh
sudo systemctl status glucopilot-monitor.timer --no-pager
sudo journalctl -u glucopilot-monitor.service -n 30 --no-pager
```

采集器：`/opt/glucopilot-monitor/collector.py`。
公共快照：`/var/lib/glucopilot-monitor/public/metrics.json`，root所有、0644。
CPU累积计数：`/var/lib/glucopilot-monitor/private/cpu.json`，仅root可读。
每次写入使用临时文件和原子替换，避免API读到半个JSON。失败时保留旧快照，由页面标记过期。首个CPU样本为null，不能当成0%使用。

API服务的Compose需要以下只读挂载，并重建API容器：

```yaml
services:
  api:
    volumes:
      - /var/lib/glucopilot-monitor/public:/app/monitor:ro
```

默认路径 `/app/monitor/metrics.json`。其他路径可以用 `SERVER_METRICS_PATH` 指定，不能从HTTP请求提供路径。开发环境未安装采集器时显示“尚未采集”，不会退回Mock或把JVM当作宿主机。

## 管理端

“系统监控 → 服务器资源”，路由 `/monitor/server`。
接口 `GET /admin/v1/ruoyi/monitor/resources`，需要现有 `monitor:server:list` 权限。
采样超过30秒会警告过期，容器采集失败单独提示。内存使用量=宿主机总量-可用量；磁盘仅根文件系统；容器内存同 `docker stats` 口径。

## 自测与停用

```bash
python3 -m unittest discover -s . -p 'test_*.py' -v
sudo systemctl disable --now glucopilot-monitor.timer
```

停用后页面将显示最后采样及过期提示。无需更改数据库，也无需向Web容器授予Docker权限。此监控不替代历史告警平台；采样历史只保留约10分钟。
