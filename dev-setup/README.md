# 本机后端开发默认配置

本目录位于 **backend 根下**，monorepo 的 `backend/` 与独立仓 `GlucoPilot-backend` 用法相同。

**用途：** 每次 `git pull` / 换机后不用手改本地配置——改默认值只动 `defaults.env`，再一键覆盖写出。

## 一键（推荐：本地存储）

双击本目录下的 **`一键配置.cmd`**

效果：

- 用 `defaults.env` **强制覆盖**写出 `backend/.env` 与 `application-local.yaml`
- `STORAGE_PROVIDER=local`（文件落在 `./data/uploads`），**不需要 Docker / MinIO**
- 内容/食物图片上传与稳定 URL 仍可用

不启动 Spring Boot；IDE Active profiles 仍用 `dev,local`。

## 可选：本机 MinIO

仅在需要真 MinIO 时：先开 Docker Desktop，再双击 **`一键配置并启动MinIO.cmd`**（会写成 `STORAGE_PROVIDER=minio` 并建桶）。一键会先检查镜像是否已下载。

## 改默认值

编辑 [`defaults.env`](defaults.env)，再双击 `一键配置.cmd`（或命令行加 `-Force`）。

- `OPS_TOKEN_SECRET`：≥64 字符  
- `STORAGE_PROVIDER`：默认 `local`  
- `MINIO_*`：仅在 `-WithMinio` 时才会切到 minio

## 命令行（备选）

在 **backend 根目录**：

```powershell
powershell -ExecutionPolicy Bypass -File .\dev-setup\setup-backend-dev.ps1 -Force
powershell -ExecutionPolicy Bypass -File .\dev-setup\setup-backend-dev.ps1 -WithMinio -Force
```

## 生成文件（勿提交）

| 文件 | 用途 |
| --- | --- |
| `.env`（backend 根） | Compose / `application-dev.yaml` 可选导入 |
| `glucopilot-business/src/main/resources/application-local.yaml` | IDE `dev,local` 注入 `token.secret` |

不自动启动 MySQL / Redis。
