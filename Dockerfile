# syntax=docker/dockerfile:1
# 构建上下文固定为 backend/，避免把前端、文档和本机构建产物带入镜像。
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY . .
RUN mvn -B -q -pl ruoyi-admin -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S glucopilot && adduser -S glucopilot -G glucopilot
COPY --from=build /workspace/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar

RUN mkdir -p /app/uploads && chown glucopilot:glucopilot /app/uploads

USER glucopilot
EXPOSE 8080

# MySQL、Redis 与密钥均通过容器环境变量注入，详见根目录 .env.example。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
