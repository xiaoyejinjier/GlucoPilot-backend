# 独立仓库部署入口

拆仓后以 [独立部署手册](../DEPLOYMENT.md) 为准。
后端与管理端分别维护 compose.yaml，通过 glucopilot-shared 外部网络通信。
原单仓库 compose.server.yaml 的相邻源码构建路径已不适用于独立仓库。
