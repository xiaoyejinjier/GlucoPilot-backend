# 数据库脚本说明

## 推荐：Flyway 自动迁移（默认）

后端启动时会执行 [`src/main/resources/db/migration/`](../src/main/resources/db/migration/)：

| 版本 | 文件 | 内容 |
|------|------|------|
| V1 | `V1__init_schema.sql` | 38 表结构 |
| V2 | `V2__init_seed.sql` | 字典/角色/权限/CMS/营养库等种子 |
| V3 | `V3__ops_dev_login.sql` | 联调 B 端账号 `admin/admin123`、`editor01/editor123` |

前提：MySQL 可连（`application.yaml` 默认 `root/123456`），JDBC 已带 `createDatabaseIfNotExist=true`，**无需再手工导入整库 SQL**。

```bash
cd backend
.\mvnw.cmd spring-boot:run
```

## 全新 clone 最短路径

1. 安装并启动 MySQL 8  
2. 改好 `application.yaml` 里数据源账号密码（若不同）  
3. 启动后端 → Flyway 自动建库并迁移到 V3  

## 已从旧流程手工导入过的库

若库里**已有表**且没有 `flyway_schema_history`，直接开 Flyway 会报「表已存在」。

任选其一：

**A. 开发环境清库重来（最简单）**

```sql
DROP DATABASE IF EXISTS sugar_health;
```

再启动后端，Flyway 从 V1 跑满。

**B. 保留数据，只做 baseline（结构已与 V1–V3 一致时）**

```bash
cd backend
.\mvnw.cmd -Dflyway.url="jdbc:mysql://127.0.0.1:3306/sugar_health" -Dflyway.user=root -Dflyway.password=123456 flyway:baseline -Dflyway.baselineVersion=3
```

之后启动应用，Flyway 只跑 V4 及以后的新脚本。

## `intelligent_health_assistant.sql`

全量脚本（含 `DROP TABLE`），用于**对照设计 / DBA 手工整库重建**，日常开发以 Flyway 为准。内容与 V1+V2 对齐（UA 字段已为 512）。

## `patches/`（历史）

联调补丁已并入 Flyway **V3**；`20260916_user_agent_512` 已合入主 DDL（V1），**新环境不必再执行 patches**。旧库若仍是 256 长度 UA，可单独执行该 patch 或清库走 Flyway。
