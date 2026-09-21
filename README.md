# GlucoPilot Backend

Spring Boot 4 / Java 21 服务端。提供用户端 `/api/v1/**`、管理端 `/admin/v1/**` 与设备血糖同步接口，统一返回 `ApiResponse`。

## 配置

MySQL 和 Redis 由远程环境统一部署。运行前在仓库根目录复制 `.env.example` 为 `.env`，将环境变量注入 IDE、Shell 或容器；不要把真实连接信息写入 `application.yaml`。

```bash
./mvnw test
./mvnw spring-boot:run
```

## 容器

Docker 构建上下文必须是本目录：

```bash
docker build -t glucopilot-api:local .
```

完整的 Nginx 入口和组合部署请从仓库根目录使用 `compose.server.yaml`，说明见 [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md)。
