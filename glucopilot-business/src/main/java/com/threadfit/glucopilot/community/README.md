# 社区模块：按功能组织代码

目录规则为 `community/功能/技术层/类名.java`。每个功能按需要包含 `controller`、`service`、`mapper`、`dto`、`entity`，不为没有对应代码的技术层创建空文件。

| 功能目录 | 功能 | 主要实现位置 |
| --- | --- | --- |
| `message` | 私信、首条联系限制、消息已读、会话列表、管理端信息管理 | `message/service/CommunityMessageService.java`、`AdminMessageService.java` |
| `comment` | 评论、回复、定位评论、我的评论、管理端评论管理 | `comment/service/CommunityCommentService.java`、`AdminCommunityCommentService.java` |
| `follow` | 关注、取消关注、我的关注 | `follow/service/CommunityFollowService.java` |
| `reaction` | 点赞、收藏、计数更新、我的点赞/收藏 | `reaction/service/CommunityArticleActionService.java`（写入）、`CommunityReactionService.java`（列表） |
| `profile` | 社区公开资料、作者身份、用户搜索、关注状态读取 | `profile/service/CommunityProfileService.java` |
| `article` | 投稿、修改、文章列表、作者文章、管理员终审 | `article/service/CommunityPublicationService.java` |
| `draft` | 存草稿、编辑草稿、发布草稿 | `draft/service/CommunityDraftService.java` |
| `tag` | 社区标签、管理员新增/停用/删除标签 | `tag/service/CommunityTagService.java` |
| `report` | 提交举报、管理端处理举报、本人举报反馈和已读 | `report/service/CommunityReportService.java`、`CommunityReportFeedbackService.java` |
| `notification` | 互动消息汇总、通知列表、已读、通知创建 | `notification/service/CommunityNotificationService.java`、`CommunityEventService.java` |
| `recommendation` | 用户兴趣向量、文章评分、管理端前20名和计算解释 | `recommendation/service/CommunityRecommendationService.java`、`AdminRecommendationService.java` |
| `access` | 文章公开/私密/指定好友可读、隐藏文章、浏览记录 | `access/service/ArticleAccessService.java` |
| `moderation` | AI审核队列、任务领取、结果解析、评论隐藏/恢复 | `moderation/service/CommunityModerationQueue.java`、`CommunityModerationWorker.java` |
| `media` | 文章图片/视频鉴权读取、视频首帧、文章媒体列表 | `media/service/CommunityArticleImageService.java`、`CommunityVideoCoverService.java` |
| `shared` | 跨模块协调、账号注销社区数据清理、通用参数校验和查询投影转换 | `shared/service/CommunityService.java`、`shared/support/CommunityValidation.java` |

## 先看私信

1. `message/controller/CommunityMessageController.java`：私信HTTP入口，路径仍为 `/api/v1/community/messages`。
2. `message/service/CommunityMessageService.java`：`send`发送、`messagePermission`首条联系规则、`messages`历史消息分页、`read`已读、`inbox`会话列表、`unread`未读提醒。
3. `message/mapper/CommunityMessageMapper.java`：账号行锁、请求编号回查、消息写入、会话和未读查询。
4. `message/entity/CommunityMessage.java`：映射 `community_message` 表。
5. `message/dto/MessageDtos.java`：发送请求、发送权限、消息和会话响应。

`send`先按统一账号顺序锁定双方，在同一Spring事务内检查幂等和发送权限，再保存消息。未关注可发第一条；收到对方消息或互相关注后可继续。相同请求编号必须匹配原接收人和正文，不重复投递。

## 点赞和收藏

`reaction/controller/CommunityArticleActionController.java`接收原 `/api/v1/news/{id}/actions` 请求；`CommunityArticleActionService`保留原文章行锁、状态差值、计数及通知事务。`NewsUserAction`与`NewsUserActionMapper`随实现归入本功能。资讯模块 `ContentService.updateNewsAction`仅保留委托入口，实际写入不再混在资讯读取逻辑中。

## 推荐代码阅读顺序

1. `article/service/CommunityPublicationService.java`：组装候选过滤，推荐只改变排序。
2. `recommendation/mapper/CommunityRecommendationMapper.java`、`CommunityRecommendationSql.signals()`：读取近30个自然日本人的有效阅读、点赞、收藏；权重分别为1、3、5。
3. `recommendation/service/CommunityRecommendationService.java`：14天半衰期、标签权重累加、前64维、L2归一化；中文步骤注释保留。
4. `recommendation/mapper/CommunityRecommendationSql.java`：余弦相似度60、热度15、新鲜度15、关注10，同分按发布时间/ID倒序，SQL排序后分页。
5. `recommendation/service/AdminRecommendationService.java`：只读计算目标用户可读文章前20名、向量和分项解释，复用同一公式，不新增浏览记录。

冷启动时兴趣分为0，其他分项仍然生效。推荐权重是初始规则，尚未经过线上A/B优化。每次请求重新计算，跨页行为变化可能改变排序；前端仍按ID去重。关键词搜索、关注列表使用最新排序。

## 共用代码与兼容

- `shared/service/CommunityService.java`是供资讯、翻译、通知等既有调用方使用的协调入口，只委托功能Service，不承载SQL或私信/评论等业务规则。各功能Controller直接调用本功能Service。
- 投稿Service的举报和管理员评论旧方法同样只委托对应功能实现，便于既有调用方迁移。
- 生产持久化继续统一使用MyBatis/MyBatis-Plus；绑定参数、Spring事务、行锁、幂等、审核版本控制和媒体鉴权保留。
- 社区按功能增加包层级；两种启动方式递归扫描社区包，并仅注册BaseMapper或显式`@Mapper`接口。SQL工具和普通业务接口不注册成Mapper。
- 本次只重组代码与依赖，不改变HTTP路径、JSON字段、数据库表、迁移脚本或个人配置。

## 验证和启动

测试按对应功能放在 `src/test/java/com/threadfit/glucopilot/community/功能/service`，共享数据库夹具在`shared/support`。测试中的JDBC只用于隔离数据库建表、准备数据和断言，业务查询运行真实MyBatis。

包结构发生变化后，在IDEA停止后端，执行 **Build → Rebuild Project** 清理旧包编译产物，再重新运行；若残留旧Controller，可能出现重复接口映射。功能分包本身无需迁移数据库或重新上传媒体。2026-11-02合入的远端账号注销与AI配置另含V45至V51迁移，由Flyway在启动时按顺序执行。

批量待审核科普素材导入见后端仓库根的 `tools/USER-ARTICLE-IMPORT.md`；该显式本地运维工具不在应用启动时自动执行。
