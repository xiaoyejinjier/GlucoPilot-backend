# GlucoPilot Backend

官方若依多模块后端，Spring Boot 4.1.1 / Java 21。现有用户端 /api/v1 与管理端 /admin/v1 路径、界面和 ApiResponse 契约保持。

## 构建与启动

在本目录执行（需 Maven 3.9+）：

```bash
mvn test
mvn package
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev,local
```

IDE 运行入口为 com.ruoyi.RuoYiApplication。公共配置和 Flyway 文件在 glucopilot-business/src/main/resources；个人配置放该目录的 application-local.yaml（Git 忽略），或注入环境变量。Shell/IDE 不会自动读取仓库根 .env；Docker Compose 会读取。必须配置 MySQL、Redis、至少 64 字节的 OPS_TOKEN_SECRET、DeepSeek API Key 和需要使用的对象存储。

官方配置类的 upstream-standalone profile 仅用于标记保留的上游实现，不能与本项目运行 profile 一起启用。

## 迁移集成测试

默认 mvn test 运行不依赖数据库的业务回归。完整集成测试单独启用，需要一个允许创建测试库的 MySQL 8 测试实例：

```bash
export MIGRATION_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/'
export MIGRATION_MYSQL_USERNAME=root
# 可通过 MIGRATION_MYSQL_PASSWORD 注入测试实例密码，禁止提交。
mvn verify -Pmigration-it
```

测试只在随机 gp_ruoyi_it_* 新库执行 V1–V9、写入测试数据、再执行 V10，结束删除此测试库。Redis 使用 test scope 的 embedded-redis 在随机端口启动并关闭；不会连接部署 Redis。未设置 MIGRATION_MYSQL_URL 时集成测试跳过，不能据此认定迁移已验证。

本机 JDK 25 在中文用户临时目录可能出现 Mockito agent 加载失败，可用 -DargLine=-Djava.io.tmpdir=C:/Windows/Temp 指向有写权限的英文临时目录。生产运行基线仍为 Java 21。

## 容器

```bash
docker build -t glucopilot-api:local .
```

构建上下文固定为仓库根目录。唯一可执行产物是 ruoyi-admin/target/ruoyi-admin.jar。远程服务与 Nginx 部署见 [部署手册](docs/DEPLOYMENT.md)，数据库切换见 [迁移手册](docs/RUOYI-DATABASE-MIGRATION.md)。


## 独立仓库

当前目录就是构建根目录，Docker 构建使用 `docker build -t glucopilot-api:accepted-tag .`。
独立部署步骤见 [DEPLOYMENT.md](DEPLOYMENT.md)。

无 AI Key 部署：服务器 .env 设置 `SPRING_AI_MODEL_CHAT=none`、`AI_VISION_ENABLED=false`，不填写虚假 API Key。实际调用文本 AI 返回业务拒绝；其他功能仍需数据库、Redis 和登录签名密钥。恢复 AI 时设置 `SPRING_AI_MODEL_CHAT=deepseek` 和真实 Key，重建容器。

课程演示无需开通 OSS：设置服务器 `.env` 中 `STORAGE_PROVIDER=disabled`，不创建对象存储客户端，也不需要 OSS 凭据。上传及对象读取明确拒绝，不返回模拟成功。AI 仍需单独设置 `SPRING_AI_MODEL_CHAT=none`、`AI_VISION_ENABLED=false` 关闭。恢复存储时选择已有 provider 并填写真实配置，重建容器。

本机联调默认用本地文件存储：双击 `dev-setup/一键配置.cmd`（或见 `dev-setup/README.md`），会按 `defaults.env` 写出 `.env`（`STORAGE_PROVIDER=local`），无需 Docker。可选本机 MinIO：开 Docker 后双击 `dev-setup/一键配置并启动MinIO.cmd`（自动建桶 `glucopilot`）。
