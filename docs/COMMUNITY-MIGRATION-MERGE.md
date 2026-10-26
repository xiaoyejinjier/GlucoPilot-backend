# 社区迁移与主线合并（2026-10-20）

## 2026-10-22 V36编号合并（当前主线）

远端已发布`V36__community_comment_like.sql`，原文件及校验和保持不变。本机尚未上传的举报反馈/文章权限脚本只调整文件名，不改SQL内容：

| 本机旧文件 | 当前主线文件 |
| --- | --- |
| V36__community_report_feedback.sql | V37__community_report_feedback.sql |
| V37__community_visibility_views_preferences.sql | V38__community_visibility_views_preferences.sql |

从原GitHub主线升级（V36为comment_like）或空库，正常启动即可执行后续V37/V38。只有曾运行上述未上传旧文件的开发库需要兼容处理，不能直接repair或改校验和。受限工具`tools/ReconcileFeedbackMigrations.java`与`tools/ReconcileCommunityMigrations.java`一起编译，运行参数为`audit|apply workspace expectedLocalDatabase`；只允许已核对的本机开发库。工具精确检查旧文件名、描述、Flyway校验和及版本集合，apply先完整备份、在隔离副本演练，再把对应历史映射到V37/V38并仅该次乱序补齐原V36评论点赞表；不重复执行已有表的ALTER，不改校验和，不向新表写假数据。正常配置校验成功后无需启用outOfOrder。

本机已完成上述处理：96张表完整备份到工作区`.tmp/community-history-backup-20261022-095818-981`，95张原业务表逐行SHA-256未变；隔离副本演练、38项迁移正常校验及pending=0通过，临时副本已清理，私密备份不提交GitHub。其它部署库不能照搬本机历史，应按实际脚本记录选择正常升级或独立核对。

下文旧V22—V28转换工具现在明确以V35为目标，避免将后续新增脚本混入首次兼容转换。完成V35校验后，再由正常应用启动执行V36及之后迁移。

## 2026-10-20 首次拆仓合并

GitHub 主线的 V22—V28 已用于 AI 记忆、邮箱及设备功能，保持原文件和校验和。此次首次上传的社区系列顺延，SQL 内容保持不变：

| 本地开发旧版本 | 合并后版本 | 内容 |
| --- | --- | --- |
| V22 | V29 | 社区互动与私信 |
| V23 | V30 | 食品来源照片 |
| V24 | V31 | 社区投稿、分类及审核 |
| V25 | V32 | 评论回复 |
| V26 | V33 | 文章图片 |
| V27 | V34 | 草稿、信息通知与真实导出 |
| V28 | V35 | 文章视频 |

空库以及按原 GitHub 主线升级的数据库正常运行 Flyway 即可。没有修改既有主线迁移，也没有在上传时操作任何数据库。

本地曾应用旧社区 V22—V28 的开发库不能直接重启升级，不能用 `repair` 将不同 SQL 的同号记录强行通过校验。需要先备份并核对 `flyway_schema_history` 的版本、描述、脚本和校验和，再安排一次受控转换：只将匹配旧社区迁移的历史记录映射到 V29—V35，保留已有业务表和数据；随后仅该次升级启用 Flyway `outOfOrder`，补齐主线缺失的 V22—V28，全部校验成功后关闭该选项。不要对不符合此前提的数据库执行此转换。

转换属于单独的数据库维护步骤，应在确认目标库及备份后执行。本次代码上传没有自动转换本机库或启用跳过校验。

## 2026-10-21 本机兼容转换已完成

根据本机启动日志，已核对旧社区7条记录的文件名、描述与校验和，完整备份90张表，在隔离副本验证后再转换原库；35条迁移全部校验通过，待执行数为0。68张原业务表的数据摘要一致。临时乱序执行仅用于该次补齐，应用配置未增加此开关，后续按正常配置启动即可。

受限操作工具为 `tools/ReconcileCommunityMigrations.java`，仅接受明确核对的本机开发库；`audit` 只读，`apply` 必须先备份、在隔离副本试跑并比较原业务数据后才处理原库。生成列不作为显式插入字段，避免复制/恢复失败。重复执行只进行正常校验，不再次改历史或重复迁移。其它部署数据库仍须独立核对，不能照搬当前库的历史记录。

本次有效备份在工作区 `.tmp/community-history-backup-20261021-091356-148/`，逐表行数与文件SHA256见 `MANIFEST.txt`；备份含用户数据，不提交GitHub。

Flyway乱序补齐行为参考[官方outOfOrder说明](https://documentation.red-gate.com/fd/flyway-out-of-order-setting-277579015.html)。
