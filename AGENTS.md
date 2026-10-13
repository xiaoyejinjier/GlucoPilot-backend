# GlucoPilot 协作规则

## 边界与归属

- `backend/`：Spring Boot API、数据库迁移和数据同步；仅后端负责人修改。
- `admin/`：Vue 管理端；按菜单/页面分配负责人，未经接口确认不得改后端 DTO。
- `frontend/`：微信小程序用户端；按页面分配负责人。
- 跨端需求先更新 `docs/API-CONTRACT.md`，再实现调用方与服务端。

## 接口与数据规则

- 公开入口只有 Nginx：用户端 API 为 `/api/v1/**`，管理端 API 为 `/admin/v1/**`。
- 所有 HTTP 响应必须使用 `ApiResponse`，即 `{ code, message, data, requestId }`；成功码为 `0`。
- 新接口必须版本化、校验请求参数、保留 `X-Request-Id`，写操作评估 `X-Idempotency-Key`。
- 禁止前端页面直接拼服务器地址；用户端只从 `frontend/utils/config.js` 读取地址，管理端生产环境使用相对路径。
- 密码、令牌、服务器 IP、数据库和 Redis 地址只放在服务器 `.env` 或密钥系统中，绝不能写进代码或文档示例的真实值。

## 开发与发布

- 团队开发和测试连接运维提供的远程 MySQL、Redis；本地容器不得作为共享数据源。
- 仅使用 `main`（集成）和 `release`（生产发布）两个长期分支；`release` 只接受已验收的 `main` 提交。
- 页面负责人完成页面、接口负责人完成接口后，分别进行联调和验收；接口完成即提交给运维部署，不由开发者直接登录生产修改。
- 后端和管理端的 Dockerfile 必须位于各自根目录，镜像构建上下文不能越过该目录。
- 每天开始前登记页面/接口负责人、依赖和验收条件；结束前更新进度、风险与次日计划。

## 拆仓后的路径
当前仓库是原 backend/ 的内容，原目录前缀已移除。
接口权威契约位于 GlucoPilot-backend/docs/API-CONTRACT.md；跨端变更先更新契约，再同步到调用方。
