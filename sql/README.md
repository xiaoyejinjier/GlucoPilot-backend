# 数据库

日常使用 Flyway：`src/main/resources/db/migration/`（V1 表结构、V2 种子、V3 联调账号）。

现有数据库增加 C 端用户名的独立升级脚本为：

```text
src/main/resources/db/migration/V6__add_user_account_username.sql
```

启动后端时 Flyway 会自动执行该脚本：为 `user_account` 增加 `username`，优先用
`user_profile.nickname` 回填，昵称为空时使用 `用户_<用户ID>`。不要修改已经执行过的
V1 文件，否则会触发 Flyway 历史校验失败。

```bash
cd backend
./mvnw spring-boot:run
```

MySQL 可连且 `application.yaml` 账号正确即可；JDBC 带 `createDatabaseIfNotExist=true`。
