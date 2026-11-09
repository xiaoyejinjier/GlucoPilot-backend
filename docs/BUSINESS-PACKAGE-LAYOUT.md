# 业务代码分层与位置

2026-11-06整理。以下Java路径均相对于`glucopilot-business/src/main/java/com/threadfit/glucopilot/`。

采用“业务功能 → 职责分层”的目录。`controller`接收请求与检查入口权限，`service`负责业务规则和事务，`mapper`负责MyBatis读写，`entity`保存表映射或查询投影，`dto`定义请求与返回，`repository`封装数据读取和转换，`support`放渲染、判定等辅助代码，`job`放定时任务。有接口和实现分离时，实现位于`service/impl`；无接口的Service直接放`service`，不额外创建空接口。

## 导出任务

```text
export/
├── controller/
│   └── DataExportController.java
├── service/
│   └── DataExportService.java
├── dto/
│   └── DataExportDtos.java
├── mapper/
│   ├── DataExportMapper.java
│   └── DataExportQuery.java
├── entity/
│   ├── DataExportTaskRow.java
│   └── DataExportFileRow.java
└── support/
    └── ExportPdf.java
```

讲解导出业务时，打开[DataExportService](../glucopilot-business/src/main/java/com/threadfit/glucopilot/export/service/DataExportService.java)。请求与任务对象已独立为[DataExportDtos](../glucopilot-business/src/main/java/com/threadfit/glucopilot/export/dto/DataExportDtos.java)，JSON字段与原来一致。

## 个人功能的实现位置

| 功能 | 实现位置 |
| --- | --- |
| 私信、首条限制、幂等与并发锁 | `community/message/service/CommunityMessageService.java` |
| 餐前餐后实测记录 | `glucose/record/service/GlucoseRecordService.java` |
| 时段数据 | `glucose/period/service/GlucosePeriodService.java` |
| 血糖趋势数据整理 | `glucose/trend/service/GlucoseTrendService.java` |
| 正餐记录与营养快照 | `meal/service/impl/MealRecordServiceImpl.java` |
| 日统计与打卡 | `statistics/daily/service/DailyStatisticsService.java` |
| 周统计 | `statistics/weekly/service/WeeklyStatisticsService.java` |
| 周视图预测值汇总 | `statistics/weekly/service/MealPredictionStatisticsService.java` |
| 月视图与月统计 | `statistics/monthly/service/MonthlyStatisticsService.java` |
| 趋势统计 | `statistics/trend/service/TrendStatisticsService.java` |
| 异常事件处理、冲突校验与操作留痕 | `glucose/alert/service/GlucoseAlertService.java` |
| 异常事件数据访问与类型转换 | `glucose/alert/repository/GlucoseAlertRepository.java` |
| 九时段健康记录管理 | `glucose/admin/service/AdminHealthRecordService.java` |
| 用户反馈 | `user/feedback/service/FeedbackService.java` |
| 管理端反馈审批 | `user/feedback/service/AdminFeedbackService.java` |
| 用户账号与资料 | `user/account/service/UserAccountService.java` |
| 管理端用户管理 | `user/admin/service/AdminUserService.java` |
| 用户设置与应用配置 | `user/settings/service/UserSettingService.java`、`AppConfigService.java` |
| 用户提醒与已读 | `notification/service/UserReminderService.java` |
| 管理端待办提醒 | `notification/service/AdminWorkReminderService.java` |
| 食物营养库 | `food/service/FoodService.java` |
| 管理端食物营养库 | `food/service/AdminFoodService.java` |
| 社区举报与举报反馈 | `community/report/service/CommunityReportService.java`、`CommunityReportFeedbackService.java` |
| 推荐算法与管理端解释 | `community/recommendation/service/CommunityRecommendationService.java`、`AdminRecommendationService.java` |
| 管理端信息管理 | `community/message/service/AdminMessageService.java` |
| 导出任务 | `export/service/DataExportService.java` |

通知、血糖告警、健康记录管理的Controller和DTO已分别放到对应功能的`controller`、`dto`目录。用户注销也按`service`、`service/impl`、`job`分层。原来平铺的内容媒体、翻译和资源监控已补齐Controller、Service和DTO层。

Mapper接口和既有XML路径保持对应。数据库迁移脚本继续在`resources/db/migration`，属于表结构管理。此次整理未改变HTTP地址、JSON字段、权限、事务或数据库结构。

移动后需要重新构建后端并重启进程。IDEA旧标签页可能仍指向原文件，应按新目录打开；小程序和管理端无需为目录调整修改调用。
