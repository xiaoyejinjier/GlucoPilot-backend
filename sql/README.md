# 数据库

日常使用 Flyway：`src/main/resources/db/migration/`（V1 表结构、V2 种子、V3 联调账号）。

```bash
cd backend
./mvnw spring-boot:run
```

MySQL 可连且 `application.yaml` 账号正确即可；JDBC 带 `createDatabaseIfNotExist=true`。
