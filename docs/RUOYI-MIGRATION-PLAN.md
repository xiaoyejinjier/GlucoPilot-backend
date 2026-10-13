# GlucoPilot 官方若依后端迁移方案

日期：2026-10-09。评估源码：`9a3ba1b5f9ad9cee49bf465ba2c012b1c169b389`。

本文件保留迁移前的设计与源码评估。代码迁移已按该范围实施：整个后端基于官方若依，保留微信小程序与 Vue 管理端界面。实施结果见 [集成说明](RUOYI-INTEGRATION.md)、[工作记录](MIGRATION-WORKLOG.md) 和 [数据库迁移手册](RUOYI-DATABASE-MIGRATION.md)。目标环境数据库未提供连接信息，尚未执行远端变更或部署。

## 1. 决策与现状

采用官方 RuoYi-Vue 的 Spring Boot 4 后端作为 Maven 多模块骨架，将既有控糖业务移入自定义模块，由 `ruoyi-admin` 统一启动、打包和提供 API。最终仍是一个后端服务和一个 Nginx 公网入口。

现有仓库已经有若依风格功能，但尚不是官方若依多模块后端：

| 已核查内容 | 现状 | 迁移含义 |
| --- | --- | --- |
| `backend/pom.xml` | 单模块；Java 21、Boot 4.1.1、MP 3.5.17、Spring AI 2.0.1、JJWT 0.13.0 | 业务依赖保留，依赖版本改由根 POM 集中管理 |
| `docs/RUOYI-INTEGRATION.md` | 明确说明属于兼容接入，没有直接采用官方多模块 | 本次目标是引入官方基础模块并实际调用其服务 |
| `auth/service/RuoYiPermissionService` | 自定义 `@Service("ss")`，从既有运营表查询权限 | 替换为官方 PermissionService，避免同名 Bean |
| `auth/service/OpsAuthService` | UUID 令牌，写入 `ops_session`，默认 8 小时 | 后台切换到若依认证和 Redis 登录状态 |
| `auth/security/UserSessionAuthFilter` | 小程序独立会话，写入 `RequestContext.userId` | 保留在若依应用中的 C 端扩展认证 |
| `ruoyi/` | 自建系统、审计、调度、生成服务 | 逐项由官方实现替换，保留旧接口 DTO 适配 |
| `user/settings/AppConfigService` | 依赖自建 `SysConfig` 的分组、类型字段 | 此表是业务配置，必须从系统参数表中独立出来 |
| `frontend/` 与 `admin/` | 微信原生小程序；Vue3/TS/Element Plus 管理端 | 保留页面，只按需要调整请求层和权限刷新 |

官方当前 master 支持 Boot 4；选择一个已核实的提交并记录 SHA，避免直接跟随浮动分支。以现有 Java 21 / Boot 4.1.1 为目标基线做兼容验证，首轮验证不通过时先处理依赖差异。此次不引入微服务，也不改用第三方若依衍生版本。

## 2. 目标工程

```text
GlucoPilot/
├── frontend/                      原有小程序
├── admin/                         原有 Vue 管理端、Nginx
├── backend/                       Maven 聚合工程、Docker 构建上下文
│   ├── pom.xml
│   ├── ruoyi-admin/               唯一启动模块、环境配置、Flyway
│   ├── ruoyi-common/              官方公共模型和工具
│   ├── ruoyi-framework/           官方安全、日志等基础能力
│   ├── ruoyi-system/              官方用户、角色、菜单等服务
│   ├── ruoyi-quartz/              官方任务模块
│   ├── ruoyi-generator/           官方生成器
│   ├── glucopilot-business/       既有控糖业务及 C 端扩展认证
│   └── glucopilot-adapter/        既有管理 API、DTO、上下文适配
└── docs/
```

依赖方向：`ruoyi-admin → glucopilot-adapter → glucopilot-business`；适配模块可以调用 `ruoyi-system/framework`，业务模块按实际需要依赖 `ruoyi-common`。不让官方系统模块反向依赖控糖业务。

业务包名继续使用 `com.threadfit.glucopilot`，避免批量改包造成无谓返工；官方模块保留 `com.ruoyi`。启动模块显式扫描两个包，但只扫描需要保留的配置与组件；旧主类、旧安全配置和被替换的自建系统服务不能重复注册。

业务模块第一轮按原有包结构整体搬迁：`glucose`、`meal`、`statistics`、`prediction`、`profile.health`、`user`、`food`、`content`、`device`、`file`、`ai`。先保持算法、SQL、对象存储、AI 模型与业务事务不变，待迁移完成后再决定是否继续拆细。

## 3. 页面与接口兼容

保持既有公网路径：小程序 `/api/v1/**`，管理端 `/admin/v1/**`。管理端旧系统入口 `/admin/v1/ruoyi/**` 由适配 Controller 调用官方系统 Service；不要仅用 Nginx 改路径，因为请求方法、字段名、状态值和响应格式均有差异。

普通业务响应继续为：

```json
{"code":0,"message":"success","data":{},"requestId":"..."}
```

分页继续使用 `data.records/pageNum/pageSize/total/pages`。当前两个前端都判断 `body.code === 0`，直接改成官方 AjaxResult 会让成功请求也被判为失败。

设计具体的边界适配：

- 管理端登录仍为 `POST /admin/v1/auth/sessions`，入参沿用 `username/password`；内部调用若依登录逻辑，出参仍含 `accessToken/expiresIn/opsId/roles/permissions/menus` 等既有字段。
- 退出仍为 `DELETE /admin/v1/auth/sessions/current`，内部撤销若依 Redis 登录状态。
- 用户信息和菜单接口仍按旧 DTO 返回，将若依角色、菜单、权限模型转换为现有页面需要的结构。
- 官方系统 Service 常使用独立的读、增、改、删权限。为现有 `system:*:manage` 等权限建立显式转换关系，迁移时向角色授予其覆盖的官方权限；不通过超级管理员兜底普通角色。
- 各个适配 Controller 明确转换输入和输出；统一处理认证失败、权限拒绝、业务异常，并保留 requestId。避免同一异常被两个全局异常处理器竞争处理。
- SSE 接口维持 `text/event-stream` 和 `delta/done/error` 事件，不套 JSON 响应包装；下载和文件字节响应也保持原协议。

官方未适配的 `/login`、`/system/**`、`/monitor/**`、`/tool/**` 等接口不直接成为新增公网入口。逐项限定后台身份和权限；调整官方 Controller 映射或仅启用适配入口，避免 C 端身份通过兜底规则进入管理功能。

## 4. 认证与身份

同一若依应用使用路径明确的两条 Spring Security 过滤链：

1. C 端链：仅匹配 `/api/v1/**`，保留现有微信、手机登录和 `user_session`，保持 `RequestContext.requireUserId()` 的行为和用户数据归属检查。
2. B 端链：匹配 `/admin/v1/**`，使用若依 JWT 过滤器、LoginUser、TokenService 和 Redis 会话；官方系统接口若保留，也必须进入 B 端链。业务权限由官方 `@ss.hasPermi` 裁决。

显式配置过滤链顺序和兜底规则，让 C/B 令牌不能互用。保留 OPTIONS、必要公开读接口和已有 SSE ASYNC/ERROR 派发处理；在初始 REQUEST 派发完成认证，异步业务所需身份显式传入，不依赖线程池继承 ThreadLocal。

通过 `gp_ops_identity_map(legacy_ops_id, ruoyi_user_id)` 保留后台身份映射：外部 `opsId` 和已有审计字段继续使用旧运营 ID；认证、角色与菜单使用若依 userId。新运营账号同时建立业务身份记录和映射。映射层只负责身份关联，旧 `ops_account/ops_session` 不再是后台认证权威。

旧后台令牌在切换后失效，管理员重新登录；小程序会话、用户 ID 和数据归属保持不变。现有后台密码使用 BCrypt，可在验证编码与规则一致后迁移哈希。为角色修改、账号禁用和强制下线验证 Redis 会话的及时更新或撤销。

“整个后端基于若依”落实为：C 端业务也运行在若依启动工程中，使用其基础设施，并作为扩展模块实现小程序认证；无需把小程序用户都变成若依运营账号。

## 5. 数据迁移：先处理同名不同结构

现有 `sys_role` 使用 `id/role_code/role_status`，官方 Mapper 使用 `role_id/role_key/status`；现有 `sys_menu`、`sys_config` 等也有类似差异。不能将官方初始化 SQL 直接执行到现库。

首选在运维提供的隔离测试库中复制现有数据库及 Flyway 历史，再应用新增迁移。生产切换方案使用同样演练过的数据转换；原库留作回滚基线。没有数据库权限时交付脚本与校验结果要求，由运维执行。

| 数据 | 目标 | 转换要求 |
| --- | --- | --- |
| `user_account/user_session` 和所有控糖业务表 | 保持原表、ID 与关系 | 保留用户数据及小程序令牌 |
| 既有 `sys_config` | `gp_app_config` | 保留 `config_group/value_type`；改用独立业务实体和 Mapper |
| 现有与官方重名的系统表及生成表 | 在目标库归档为 `gp_legacy_*` | 逐项盘点引用后再腾出官方表名，保留原始数据 |
| 官方系统、Quartz、生成器表 | 按固定官方版本创建 | 只建立框架所需结构，排除示例业务表与破坏性初始化语句 |
| `ops_account/ops_profile` | 官方 `sys_user` 与身份映射 | 保留旧 opsId；转换昵称、部门、状态、密码哈希 |
| `sys_ops_role/sys_role` | `sys_user_role/sys_role` | 为旧 ID 与新 ID 建立映射；转换角色编码与状态 |
| `sys_permission/sys_role_permission/sys_menu` | `sys_menu/sys_role_menu` | 按权限标识归并菜单与按钮，保留业务路由及父子关系 |
| 字典、部门、岗位、公告 | 对应官方表 | 转换主键、层级、状态、逻辑删除和时间字段 |
| 旧日志 | 归档查询或转换后导入 | 不因接入官方日志而丢弃历史追踪 |
| 自建定时任务、生成配置 | 人工核对后转换 | 不自动复制不兼容 cron、目标表达式和生成模板 |

注意状态语义：现有业务常用 1 表示正常，官方系统实体可能用字符串 0 表示正常。不得直接复制，也不能假设所有表的删除状态统一。核对每个字段后形成转换表。

保留已存在的 V1–V9 文件和校验和，新增迁移从 V10 起编号，实施时再次确认远端最新编号。新库从零安装与旧库升级都要演练；不要通过随意 baseline 或 repair 绕过迁移失败。MySQL DDL 不能作为整段事务回滚，迁移必须有备份、执行记录和独立恢复步骤。

`AppConfigService` 的 PUBLIC_GROUPS 白名单继续保留，不能把官方全部系统参数或 AI/PUSH 配置直接暴露给小程序。

## 6. 首轮必须验证的框架差异

| 差异 | 处理方式 | 验证 |
| --- | --- | --- |
| 官方 JJWT 0.9.1，现项目 0.13.0 | 集中统一版本；优先将引入的 TokenService 签发/解析适配到 0.13.0 | 登录、过期、无效签名、退出与在线用户 |
| 官方 MyBatis XML，现有 MP BaseMapper | 保留两类 Mapper；显式建立命名清楚的 SqlSessionFactory 和扫描边界 | 官方 XML 查询与 MP CRUD 同时可用 |
| 官方手工 SqlSessionFactoryBean | 业务工厂使用支持 MP 注入的 MybatisSqlSessionFactoryBean | 避免 BaseMapper 出现 Invalid bound statement |
| PageHelper 与 MP 分页 | 分属各自工厂和查询路径，共用同一数据源与适当事务管理 | 不重复分页；分页总数、SQL 与事务正确 |
| Bean 与类型别名同名 | 替换自建 `ss`、安全配置；拆出 AppConfig 等业务类；限定别名扫描范围 | 不开启 Bean 覆盖来掩盖冲突 |
| Jackson3、Long 字符串输出 | 保留现有业务序列化契约，适配官方 DTO 返回 | ID 类型、时间格式、中文、SSE 字节一致 |
| Boot4/Spring AI | 先统一 BOM 并跑启动与 AI 链路；不在迁移时改模型配置 | 文本、图片、聊天历史与对象存储可用 |
| 原 Docker 构建单模块 | 构建上下文仍为 backend，改为多模块构建，复制 ruoyi-admin 的可执行 JAR | 镜像实际从若依启动模块运行 |

上述是待执行的兼容验证，不表示当前已经编译通过。只有骨架加一条完整业务链路跑通后，才批量搬迁。

## 7. 分阶段交付与验收

以下是工作量估算，按熟悉项目的开发者计人日；数据库权限、接口覆盖量和依赖验证结果可能改变估算。粗略总计 10–16 人日，不作为完成日期承诺。

| 阶段 | 工作 | 完成条件 | 估算 |
| --- | --- | --- | --- |
| 0 | 固定两仓提交；建立接口、权限、数据和已有页面清单 | 区分已实现功能、Mock 与二期占位；可重复建立迁移基线 | 0.5–1 |
| 1 | 引入官方骨架、业务和适配模块；统一关键依赖 | 若依启动、Redis 登录、MyBatis XML 与 MP 均可用 | 1.5–2.5 |
| 2 | 在隔离库执行系统数据转换和身份映射 | 数据量、关系、状态与密码验证通过；恢复演练通过 | 1.5–2.5 |
| 3 | 后台登录、权限、上下文与返回协议适配 | 原管理端登录成功；普通角色受限；跨端令牌拒绝 | 1–2 |
| 4 | 迁移代表链路及其余业务包 | 微信/手机登录→血糖写入→统计读取；业务测试通过 | 2–3 |
| 5 | 替换系统模块、日志、调度和生成器 | 原系统页面完成字段与权限适配；官方服务实际执行 | 1.5–2.5 |
| 6 | 端到端验证、镜像、迁移和回滚交付 | 小程序和后台主流程可用；运维有可复现发布步骤 | 2 |

首条验证链路建议为：保留 C 端登录与会话 → 保存血糖 → 读取统计，同时接入 B 端若依登录 → 用户列表 → 禁用/解禁，并验证两端令牌隔离。该链路覆盖认证、业务 CRUD、统计 SQL、权限和响应格式。

AI 阶段复用现有 `ChatControllerSseWireTest`、`ChatCompletionServiceTest`、`OwnedMediaResolverTest` 等，并在真实 Nginx 链路检查首字延迟、中文、完成事件及断连。当前网关普通 API 超时为 30 秒；为 SSE 单独配置合适的读超时和关闭响应缓冲，避免直接沿用普通 JSON 请求设置。

小程序回归覆盖：登录、血糖、饮食覆盖写入、健康档案、统计、餐后预测、反馈回复、媒体上传、AI 文本和图片。后台回归覆盖：用户列表和状态、营养库、CMS、反馈、系统管理和普通角色访问。已有 Mock 或二期占位仍按基线记录，不把框架迁移扩展为补齐所有产品功能。

切换前维护窗口冻结相关写入，执行最终数据同步与校验，由运维切换后端镜像/数据库连接并执行验收。切换后产生新写入时，回滚必须处理这些增量，不能仅恢复旧镜像就认定回滚完成。遵循现有 `main/release` 和运维发布流程。

## 8. 可以展示的框架使用证据

- 后端具有官方模块的源码、版本 SHA、许可证与必要修改记录，并实际通过 `ruoyi-admin` 启动。
- 后台账号、角色、菜单、权限来自官方系统服务与目标表；禁用、退出、普通角色限制可演示。
- 控糖业务是自定义模块，血糖、统计、AI 等 API 由同一个若依应用提供。
- 审计日志、任务调度和代码生成使用官方模块，已明确转换和保留必要的任务目标白名单。
- 现有两个前端可通过兼容接口完成原有主流程。

这些是建议的技术验收证据，具体课程/项目验收仍以要求方给出的标准为准。

## 9. 参考依据

- 现有工程：`backend/pom.xml`、`docs/API-CONTRACT.md`、`docs/RUOYI-INTEGRATION.md`、`AGENTS.md`。
- [官方 RuoYi-Vue 仓库](https://github.com/yangzongzhuan/RuoYi-Vue)。
- [官方版本与模块说明](https://doc.ruoyi.vip/ruoyi-vue/)。
- [官方后台手册：权限与新建子模块](https://doc.ruoyi.vip/ruoyi-vue/document/htsc.html)。
- [官方 TokenService](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/TokenService.java)。
- [官方 MyBatis 配置](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-framework/src/main/java/com/ruoyi/framework/config/MyBatisConfig.java)。
- [MyBatis-Plus 配置说明](https://baomidou.com/reference/)。
