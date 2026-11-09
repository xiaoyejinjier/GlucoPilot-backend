# 个人负责功能的 MyBatis 代码索引

所有 Java 路径均相对于 `glucopilot-business/src/main/java/com/threadfit/glucopilot/`。业务层负责校验、权限和事务，Mapper 负责数据库读写；MyBatis-Plus 的 `BaseMapper`、查询条件构造器、MyBatis XML 和注解 SQL 都属于本项目统一的数据访问方式。

## 社区互动 SQL 的 XML 位置

V29 是 Flyway 建表迁移脚本，继续保留在 `resources/db/migration`。私信、评论、关注及博主资料的 52 条业务 SQL 已移到 XML，Java Mapper 仅保留接口方法和 `@Param` 参数声明。

以下路径相对于 `glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/`：

| 功能 | XML SQL 文件 |
| --- | --- |
| 私信、双方账号行锁、请求编号幂等、未读和会话列表 | [message/mapper/CommunityMessageMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/message/mapper/CommunityMessageMapper.xml) |
| 管理端信息管理 | [message/mapper/AdminMessageMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/message/mapper/AdminMessageMapper.xml) |
| 评论分页、回复、本人评论、幂等和删除 | [comment/mapper/CommunityCommentMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/comment/mapper/CommunityCommentMapper.xml) |
| 管理端评论管理 | [comment/mapper/AdminCommunityCommentMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/comment/mapper/AdminCommunityCommentMapper.xml) |
| 关注、取消关注和关注列表搜索 | [follow/mapper/CommunityFollowMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/follow/mapper/CommunityFollowMapper.xml) |
| 用户和博主资料、找糖友搜索 | [profile/mapper/CommunityProfileMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/profile/mapper/CommunityProfileMapper.xml) |

XML 与 Java 接口的包路径、文件名相同，MyBatis 注册接口时自动加载；`namespace` 对应接口全名，语句 `id` 对应方法名。这样独立启动和若依适配启动都能使用原有扫描配置，社区 SQL 不会进入若依专用工厂的 `mapper/**` 扫描。行锁语句在 XML 中继续配置 `useCache="false" flushCache="true"`，Service 的事务与业务校验保持不变。

## 本次替换的剩余 JDBC

| 功能 | 业务入口 | MyBatis SQL 与映射 |
| --- | --- | --- |
| 异常血糖查询、审批、审计和用户回复 | [GlucoseAlertService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/glucose/alert/service/GlucoseAlertService.java) → [GlucoseAlertRepository](../glucopilot-business/src/main/java/com/threadfit/glucopilot/glucose/alert/repository/GlucoseAlertRepository.java) | [GlucoseAlertMapper](../glucopilot-business/src/main/java/com/threadfit/glucopilot/glucose/mapper/GlucoseAlertMapper.java)、[GlucoseAlertQuery](../glucopilot-business/src/main/java/com/threadfit/glucopilot/glucose/mapper/GlucoseAlertQuery.java)、[GlucoseAlertRows](../glucopilot-business/src/main/java/com/threadfit/glucopilot/glucose/entity/GlucoseAlertRows.java) |
| 正餐照片读取 | [MealPhotoService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/meal/service/MealPhotoService.java) | [MealPhotoMapper](../glucopilot-business/src/main/java/com/threadfit/glucopilot/meal/mapper/MealPhotoMapper.java)，复用 `MediaFile` |
| 用户头像读取 | [AvatarMediaService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/file/service/AvatarMediaService.java) | [AvatarMediaMapper](../glucopilot-business/src/main/java/com/threadfit/glucopilot/file/mapper/AvatarMediaMapper.java)，复用 `MediaFile` |
| 周视图的每日推荐吃法预测值汇总 | [MealPredictionStatisticsService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/statistics/weekly/service/MealPredictionStatisticsService.java) | [MealPredictionStatisticsMapper](../glucopilot-business/src/main/java/com/threadfit/glucopilot/statistics/mapper/MealPredictionStatisticsMapper.java)、`statistics/entity/DailyMealPredictionRow.java` |
| 管理端信封提醒：举报、反馈、待审核食品 | [AdminWorkReminderService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/notification/service/AdminWorkReminderService.java) | [AdminWorkReminderMapper](../glucopilot-business/src/main/java/com/threadfit/glucopilot/notification/mapper/AdminWorkReminderMapper.java)、`notification/entity/WorkReminderCount.java` |
| 英文阅读的原文、翻译缓存和任务租约 | [LocalizationService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/localization/service/LocalizationService.java) | [LocalizationMapper](../glucopilot-business/src/main/java/com/threadfit/glucopilot/localization/mapper/LocalizationMapper.java)、[LocalizationSql](../glucopilot-business/src/main/java/com/threadfit/glucopilot/localization/mapper/LocalizationSql.java)、`localization/entity/` |

保留的关键规则：

- 异常审批仍由 Spring 事务包住：锁定源血糖记录，比较测量值、测量时间和版本，再更新处理结果、写入审计。对用户只返回公开回复。
- 图片仍按原媒体登记的 `storage_key`、桶和存储提供方读取；正餐照片继续校验归属与有效正餐关联，头像继续校验有效用户。
- 预测统计只聚合已保存的 `predict_suggest`，不修改预测算法，也不混入实测均值。
- 管理端先校验操作员权限，再执行对应提醒查询；无待办时不返回虚假条目。
- 翻译保留原文哈希、阅读权限、原子租约认领、续期、失败重试和关闭时释放租约。

## 原本已使用 MyBatis 的个人功能

按讲解顺序查找实现层即可：

| 板块 | 业务实现位置 | 数据访问位置 |
| --- | --- | --- |
| 私信 | `community/message/service/CommunityMessageService.java` | `community/message/mapper/CommunityMessageMapper.java`：双方账号行锁、请求标记查重、权限查询、消息写入 |
| 血糖记录 | `glucose/record/service/GlucoseRecordService.java` | `glucose/mapper/GlucoseRecordMapper.java` |
| 正餐记录 | `meal/service/impl/MealRecordServiceImpl.java` | `meal/mapper/MealRecordMapper.java`、`MealFoodItemMapper.java` |
| 日、周、月和趋势统计 | `statistics/daily/service/DailyStatisticsService.java`、`statistics/weekly/service/WeeklyStatisticsService.java`、`statistics/monthly/service/MonthlyStatisticsService.java`、`statistics/trend/service/TrendStatisticsService.java` | 调用记录模块服务，复用血糖与正餐 Mapper；预测汇总使用上表新增 Mapper |
| 举报与举报反馈 | `community/report/service/CommunityReportService.java`、`CommunityReportFeedbackService.java` | `community/report/mapper/CommunityReportManagementMapper.java`、`CommunityReportMapper.java` |
| 用户反馈、管理端反馈 | `user/feedback/service/FeedbackService.java`、`AdminFeedbackService.java` | `user/mapper/FeedbackMapper.java`，复用用户资料 Mapper |
| 食品营养库、食品管理 | `food/service/FoodService.java`、`AdminFoodService.java` | `food/mapper/FoodNutritionMapper.java`、`FoodCategoryMapper.java`；分类/详情的 Redis 读缓存见 `food/README.md` |
| 管理端社区标签 | `community/tag/service/CommunityTagService.java` | `community/tag/mapper/CommunityTagMapper.java` |
| 管理端信息管理 | `community/message/service/AdminMessageService.java` | `community/message/mapper/AdminMessageMapper.java` |
| 管理端用户管理 | `user/admin/service/AdminUserService.java` | `user/mapper/UserAccountMapper.java`、`UserProfileMapper.java`，复用健康档案、血糖、正餐模块 |
| 导出任务与 PDF | `export/service/DataExportService.java` | `export/mapper/DataExportMapper.java`；`export/support/ExportPdf.java` 负责 PDF 排版 |
| 管理端九时段记录 | `glucose/admin/service/AdminHealthRecordService.java` | 复用 `GlucoseRecordMapper`、`MealRecordMapper`、`GlucoseNodeMapper` 和用户 Mapper |
| 社区推荐 | `community/recommendation/service/CommunityRecommendationService.java` | `CommunityRecommendationMapper.xml` 获取行为并定义评分片段；`CommunityPublicationMapper.xml` 引用评分片段并查询候选排序；XML路径见下文 |
| 管理端推荐管理 | `community/recommendation/service/AdminRecommendationService.java` | `community/recommendation/mapper/AdminRecommendationMapper.java`，复用用户兴趣计算和文章排序 Mapper |

## 推荐部分的讲解顺序（Java SQL 拼接类已移除）

1. 打开 [CommunityRecommendationMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/recommendation/mapper/CommunityRecommendationMapper.xml)，先讲原注释【1. 行为权重】对应的 `signals` 查询：浏览1、点赞3、收藏5。
2. 打开 [CommunityRecommendationService.java](../glucopilot-business/src/main/java/com/threadfit/glucopilot/community/recommendation/service/CommunityRecommendationService.java)，讲近30天、14天半衰期、按标签累加、取前64维与L2归一化。这里生成的是用户兴趣向量。
3. 回到推荐XML，按原版【2～6】注释讲 `similarity`、`popularity`、`freshness`、`followed`、`score`：向量相似度与热度、新鲜度、关注分别乘60/15/15/10后相加。
4. 推荐XML【7】`explanationColumns` 为管理端返回分项和总分；【8】`orderBy` 按总分、发布时间、文章ID降序。两处通过 `<include>` 复用同一公式。
5. 打开 [CommunityPublicationMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/article/mapper/CommunityPublicationMapper.xml) 的 `selectArticles`，讲真正执行列表的顺序：查询字段/评分明细 → `articleSearchFrom`过滤可见候选 → 推荐排序 → 分页。`countArticles`复用同一候选过滤，保持总数与列表一致。
6. 管理端 [AdminRecommendationService.java](../glucopilot-business/src/main/java/com/threadfit/glucopilot/community/recommendation/service/AdminRecommendationService.java) 的 `report` 取得前20篇并展示向量及分数；相关资料查询见 [AdminRecommendationMapper.xml](../glucopilot-business/src/main/resources/com/threadfit/glucopilot/community/recommendation/mapper/AdminRecommendationMapper.xml)。

原 `CommunityRecommendationSql.java` 的1～8步讲解注释已迁到对应XML片段旁，旧拼接类及调用引用已删除。`ArticleAccessSql.java`仍被其他内容和翻译模块使用，因此继续保留。数据库迁移SQL继续交给Flyway，不能作为废弃查询文件清理。

## 验证与启动

- `common/mybatis/OwnedFeatureMapperRegistrationTest` 验证新增 Mapper 可以注册，并检查负责范围的生产代码没有直接 JDBC 查询。
- 原业务测试改为真实 MyBatis 执行 SQL，覆盖审批并发/回滚、图片授权与路径、汇总、提醒、翻译租约。测试中保留的 `JdbcTemplate` 仅用于建立临时表、准备数据和断言数据库结果。
- `java.sql.Date` / `Timestamp` 的兼容转换不是 JDBC 查询，无需删除。MyBatis 自身仍通过 JDBC 驱动连接数据库。
- 本次替换不新增表、不修改接口，重启后端加载即可。仓库中其他开发者负责的运维身份桥接和若依代码生成器不在本次范围内。
