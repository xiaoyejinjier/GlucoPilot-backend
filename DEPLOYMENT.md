# 独立开发与部署

本仓库构建上下文是仓库根目录，不读取其他仓库文件。
团队开发、测试使用运维提供的远程 MySQL、Redis，禁止提交真实地址或密钥。

## 开发

安装 Java 21、Maven 3.9+。运行 `mvn -B -pl ruoyi-admin -am verify`。
将运维提供的配置注入进程环境，或放入被忽略的 application-local.yaml。
运行 `java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev,local`。
Shell 不会自动加载 .env，生产 Docker Compose 使用 env_file 加载。

## 运维部署

两个仓库各自构建、发布镜像，不要求并排检出源码。镜像标签使用已验收的提交 SHA，勿使用 latest。
在同一 Docker 主机上首次执行 `docker network create glucopilot-shared`。
将 .env.example 复制为服务器 .env，填写实际配置和镜像标签，勿提交。
分别执行 `docker compose config --quiet`、`docker compose up -d`。
必须先启动后端 api，再启动管理端 gateway；后端仅内部 expose 8080，公网只开放 Nginx。
变更后端容器后，在管理端仓库执行 `docker compose restart gateway`，使 Nginx 重新解析 api 地址。
HTTPS 证书、域名与防火墙由运维配置；小程序必须使用登记过的 HTTPS 域名。
后端 uploads 使用持久卷，运维负责备份和恢复。

## 发布验收

登记后端及管理端提交 SHA、镜像标签、契约版本。
检查两类 API 的登录、鉴权、响应封装以及小程序主要业务流程。
验收后由运维部署 release 对应镜像；回滚时恢复上一镜像标签并重新 up -d。
数据库迁移不自动回滚，发布前由运维确认备份和迁移兼容性。

无 AI Key 部署：服务器 .env 设置 `SPRING_AI_MODEL_CHAT=none`、`AI_VISION_ENABLED=false`，不填写虚假 API Key。实际调用文本 AI 返回业务拒绝；其他功能仍需数据库、Redis 和登录签名密钥。恢复 AI 时设置 `SPRING_AI_MODEL_CHAT=deepseek` 和真实 Key，重建容器。
