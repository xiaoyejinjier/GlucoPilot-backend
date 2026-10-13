# 若依数据库切换

## 本次版本

V1–V9 历史迁移文件原样保留；新增 V10__official_ruoyi_schema.sql。现有业务表和历史 opsId 保留，旧系统表归档到 gp_legacy_*，业务配置为 gp_app_config。后台旧会话失效，用户端会话继续保留。不要另外执行上游 ry_*.sql：这些脚本含 DROP 和演示数据，会覆盖迁移数据。

V10 之后新增 **V11__add_chat_concurrency_constraints.sql**（AI 对话的会话/消息并发约束，见
`backend/docs/AI对话-简单接入方案.md` §23）。它只动 `chat_session` / `chat_message`，与 V10 无依赖关系，
两者先后顺序不影响结果。

## 编号冲突修正（V10 曾经重号）

并行的两条分支在同一天各自按"下一个可用编号"取了 V10：本迁移与
`V10__add_chat_concurrency_constraints.sql`。Flyway 会报
`Found more than one migration with version 10`，应用直接起不来（`MigrationFilesTest` 会当场失败）。
现已把后者改名为 **V11__add_chat_concurrency_constraints.sql**。

仓库层面改完即可；**已经执行过旧 V10（也就是本地库的 version 10 记录是
`add chat concurrency constraints` 而不是 `official ruoyi schema`）的库需要手工修正一次**，
否则 Flyway 校验会因版本 10 的 checksum/描述与实际文件不符而失败：

```sql
-- 前提：确认这一行的 description 是 add chat concurrency constraints；
--       若已是 official ruoyi schema，说明本库执行的是正确的 V10，不要执行下面的语句。
SELECT version, description FROM flyway_schema_history WHERE version = 10;

-- 1) 把当时由"并发约束"迁移建出来的对象drop掉（随后由 V11 重新建立，结果等价）
ALTER TABLE chat_message DROP INDEX uk_session_seq;
ALTER TABLE chat_session DROP INDEX uk_user_active, DROP COLUMN active_user_id;

-- 2) 删掉这条历史记录，让 V10 能按正确内容执行
DELETE FROM flyway_schema_history WHERE version = 10 AND description = 'add chat concurrency constraints';
```

之后启动应用：Flyway 会执行 V10（官方结构）再执行 V11（并发约束）。数据无需回滚——
`chat_session` / `chat_message` 里的业务数据不受影响。

## 存量库执行

1. 停止旧 API 写入。使用目标环境的私密 MySQL 配置文件导出整库（含 flyway_schema_history、触发器、存储过程）；备份保存在服务器私密目录，不能提交 GitHub。确认备份可恢复。
2. 检查 flyway_schema_history：成功版本应对应本仓库 V1–V9。没有历史表的库必须逐项核对实际结构，手动确认 baseline 版本；禁止盲目 baseline=3。公共与开发配置已关闭自动 baseline。
3. 确认 gp_legacy_* 和 gp_ops_identity_map 不存在，避免将已部分迁移的库再次改名。核对 ops_account、ops_profile、角色关联和业务数据量；若现有数据已超出旧接口长度范围，先修正来源数据。旧长日志的完整内容仍在归档表，官方日志列只复制容纳范围内的文本。
4. 配置 .env 中的 MySQL、Redis、OPS_TOKEN_SECRET（至少 64 字节随机值）、AI / 存储配置，构建并启动新 API。Flyway 会校验旧迁移 checksum，并执行 V10。执行身份需要 CREATE、ALTER、RENAME、INSERT、UPDATE 权限。
5. 检查启动日志中 Successfully applied migration 10。后台登录并核对身份映射、角色权限、业务审计；验证编辑账号被拒绝的管理入口、强退后 401、小程序登录和业务读取。菜单、在线会话、任务与生成器接口均使用旧 /admin/v1/ruoyi 路径。
6. 核对数据：sys_user 与 ops_account 的映射数量、原密码哈希一致、sys_user_role / sys_user_post 关联数、gp_app_config 条数、业务表主键与条数。任务默认暂停，逐项核对 Cron 再启用；确认后恢复外部流量。

MySQL 的 DDL 会隐式提交；迁移失败时不能依赖事务回滚。若 V10 中途失败，停止服务，恢复完整备份并查明错误，再重试；不要直接 flyway repair 后继续写入部分迁移的表。旧镜像不能使用新表结构，代码回滚必须配合同一时间点整库恢复。

## 空库

空库按 V1–V10 顺序初始化。V3 保留项目原来的开发演示账号种子，正式上线前通过管理接口修改演示账号密码并停用不需要的账号。V10 不新增官方 admin/123456 等演示账户。

## 当前执行边界

没有提供目标数据库的连接配置时，只能在隔离测试库验证并交付迁移代码；Git push 不代表目标数据库已经升级。目标环境首次启动新 API 才会执行其 Flyway 迁移。
