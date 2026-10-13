# 若依后端集成

后端基于官方 RuoYi-Vue 3.9.2 源码，使用 Spring Boot 4.1.1 / Java 21。来源提交和改动范围见 [RUOYI-UPSTREAM](../backend/RUOYI-UPSTREAM.md)，MIT 许可保存在 backend/RUOYI-LICENSE。

## 模块

| 模块 | 职责 |
| --- | --- |
| ruoyi-admin | 唯一可执行应用，入口 com.ruoyi.RuoYiApplication |
| ruoyi-common / ruoyi-framework | 官方通用工具、Security、权限表达式、JWT、Redis 会话 |
| ruoyi-system | 官方用户、角色、菜单、部门、岗位、字典、公告、配置、日志 Service 与 XML Mapper |
| ruoyi-quartz / ruoyi-generator | 官方 Quartz 调度与 Velocity 代码生成 |
| glucopilot-business | 现有控糖、内容、设备、文件、AI 业务与 HTTP 接口 |
| glucopilot-adapter | Spring 扫描、过滤器注册和两套 MyBatis 工厂适配 |

业务代码包名保持 com.threadfit.glucopilot。官方管理服务由现有版本化 Controller 调用；Vue 管理端和微信小程序界面保持原样。成功响应继续为 code=0，保留 message、data、requestId。

## 认证与权限

后台登录仍为 POST /admin/v1/auth/sessions，但实际调用官方 SysLoginService、UserDetailsServiceImpl、TokenService。JWT 只承载会话标识，Redis 中的 LoginUser 是登录态权威；登出和强退立即删除会话。账号状态和权限每次请求重新查询，停用、权限调整不能靠旧 Token 绕过。

后台权限来自官方 sys_user、sys_role、sys_menu、sys_user_role、sys_role_menu。超级管理员按启用的 super_admin 角色判定，不能依赖用户或角色编号 1。保留 @ss.hasPermi 和 @PreAuthorize。

小程序继续使用独立的 user_session 与 RequestContext.userId。/api/v1 和 /admin/v1 使用独立 SecurityFilterChain；后台 JWT 与用户端会话不能互换。现有 opsId 通过 gp_ops_identity_map 映射到若依 userId，业务审计 created_by / updated_by 继续使用历史 opsId。

官方 Redis Key 统一加 glucopilot:ops: 前缀，避免在线用户和强退操作影响其他应用。必须设置至少 64 字节的 OPS_TOKEN_SECRET；Redis、数据库、AI 密钥只由环境配置提供。

## 数据、调度和生成

Flyway V10 将同名旧系统表改名 gp_legacy_*，创建官方表并迁入账号密码哈希、角色、菜单、权限、部门、岗位、字典、公告和日志。业务表继续使用原名；分组业务参数移到 gp_app_config，避免与若依 sys_config 的结构冲突。旧后台会话失效，需重新登录。

官方任务定义保存到 sys_job，由 Quartz 执行。迁移后的任务默认暂停，启用前核对 Cron。只有 system.cleanup-sessions、system.refresh-permissions、system.health-check 三个业务键可映射到 gpTasks 方法；底层反射执行也检查同一白名单。调度器使用官方默认 RAM JobStore，启动时从 sys_job 恢复，适用于单实例，不能直接多副本部署任务服务。

代码生成调用官方导入、同步和模板预览，默认 Element Plus 模板。历史生成配置保留在归档表，需要重新导入业务表。allowOverwrite=false，现有代码不会被生成器写入覆盖。生成的官方模板是预览草稿；纳入本项目时仍需适配版本化路径、ApiResponse、业务身份和权限，不可直接部署模板。

部署与数据切换步骤见 [数据库迁移手册](RUOYI-DATABASE-MIGRATION.md)。
