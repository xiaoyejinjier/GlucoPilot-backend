# 本机后端开发默认配置

本目录位于 **backend 根下**，monorepo 的 `backend/` 与独立仓 `GlucoPilot-backend` 用法相同。

**用途：** 每次 `git pull` / 换机后不用手改本地配置——改默认值只动 `defaults.env`，再一键覆盖写出。改完生成文件后须**重启后端**（profiles：`dev,local`）。

## 一键：本地存储（推荐日常）

双击 **`一键配置.cmd`**

- 写出 `.env` + `application-local.yaml`
- `STORAGE_PROVIDER=local`，**不需要 Docker**
- 管理端图片/视频上传走同源 PUT，即可用

## 一键：本机 MinIO（对象存储联调）

1. 打开 Docker Desktop  
2. 双击 **`一键配置并启动MinIO.cmd`**

- `STORAGE_PROVIDER=minio`，拉起 MinIO、建桶 `glucopilot`
- 浏览器上传走后端同源代理再写入 MinIO（不依赖桶 CORS，兼容第三方镜像）
- 会先检查镜像（`netvark/minio` / `netvark/mc`）；已有则跳过拉取  
- 控制台：http://127.0.0.1:9001 （`minioadmin` / `minioadmin`）

同事任选其一即可；两种模式下 CMS 素材上传协议相同（凭证 → PUT → 登记）。
生产/课程部署默认也是 MinIO（见 `application-prod.yaml`），不默认阿里云 OSS。

## 改默认值

编辑 [`defaults.env`](defaults.env)，再双击对应一键。

## 命令行（备选）

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
