# API 契约

## 个人头像与草稿修复（2026-10-20）

头像沿用现有上传协议：bizType=1，取凭证、PUT文件、登记完成后，以登记返回的fileUrl调用PATCH /users/me/profile。手机临时路径/本地持久文件路径不能作为avatarUrl提交；未修改头像时省略avatarUrl，清空时传空串。上传期间不能保存资料，失败保留原头像，账号变化或页面销毁后忽略迟到结果。后端继续校验当前用户的可用AVATAR媒体，不放宽所有权。
草稿读取兼容Connector/J对DATETIME返回LocalDateTime及其他驱动返回Timestamp，保留原本的本地时间值，不强制类型转换。草稿保存经真实JSON绑定、版本校验和数据库写入验证，失败不能显示已保存或丢弃内容；不调用血糖同步接口。设置移除手工Mock血糖同步入口。

## 回复、投稿媒体与用户主页（2026-10-20）

POST /api/v1/news/{id}/comments/{targetId}/replies 明确绑定回复目标，body沿用content/clientRequestId，服务端以路径目标构造CommentRequest；不支持此路由时客户端不得降级为顶级评论。返回的rootCommentId/replyToCommentId用于原线程展示。
GET /api/v1/community/profiles/{key}/articles 按PageQuery返回该用户/发布者已公开且审核通过的Article页，不含草稿、待审或已下架内容。GET /community/tags/mine 返回本人投稿中使用过的标签；客户端另按用户ID保存最近添加的标签（包括尚未投稿的），仅作为可选项，不自动选中。
投稿/草稿增加可选videoMediaIds（最多1个），Article和Draft增加videos:[{mediaId}]。最多6图+1视频；有有效媒体时正文可为空，否则正文至少20字，标题和1～3个标签仍必填。视频走bizType=8，仅MP4，单文件不超过20MB，客户端选取时限制60秒。V35扩展媒体类型、增加草稿video_ids；已发布/草稿媒体权限与图片一致。GET /api/v1/community/article-videos/{mediaId} 和 GET /admin/v1/community/articles/{id}/videos/{mediaId} 返回鉴权MP4二进制；客户端下载为临时文件播放，管理员以鉴权Blob播放。视频不得经匿名local-objects接口访问；AI初审只审文字，管理员需查看媒体后终审。
私信支持下拉刷新，不重复消息，不清空已加载历史；双方头像跳转公开用户主页，用户主页只含公开资料与已发布内容。

## 草稿箱、信息中心、真实导出与用户详情（2026-10-20）

发布按钮保持AI初审+管理员终审，返回待审状态，不绕过审核。草稿独立存储于V34 community_article_draft，不进入公开列表或审核队列：GET/POST /api/v1/community/drafts，GET/PUT/DELETE /drafts/{id}，POST /drafts/{id}/publish。写入title/body/tagCodes/customTags/imageMediaIds可为空（标题80/正文8000/标签合计3/图片6），需clientRequestId；修改需revision，创建按用户+请求标识幂等。可以携带sourceArticleId/sourceRevision保存已有文章的修改草稿，保存不改变线上版本；发布时验证原文章版本。发布请求{revision,clientRequestId}使用存储的内容，校验完整发文要求，同事务投稿/关闭草稿，重复发布只返回同一篇文章。草稿仅本人可读写，列表不返回他人草稿或密钥。

发布请求成功即从草稿箱移除，不等待AI或管理员审核结果；审核拒绝也不恢复旧草稿，用户从“我的文章”修改并重投。关闭保留服务端幂等记录，失败的发布不关闭草稿。编辑页返回时若存在未保存的文字、标签或媒体变化，提示“内容尚未保存，要先存为草稿吗？”，提供“确定返回 / 存草稿”；选择保存须成功后才返回，失败保留编辑内容。

V34 community_notification持久化真实互动：LIKE/COLLECT/COMMENT/FOLLOW，参与者取登录身份，自己给自己互动不提醒；点赞/收藏/关注幂等设置不重复产生提醒，取消清除对应提醒，重新操作可产生新提醒。评论同时通知文章作者和被回复者，去重且排除本人。旧数据不伪造为新提醒。读取时过滤停用账号、删除/下架文章及不可见评论线程。

GET /api/v1/community/information/summary返回{messages,likes,collections,comments,follows,total,latestMessageId,latestSenderId,latestNotificationId}，仅当前用户未读。GET /community/notifications?kind=LIKE|COLLECT|COMMENT|FOLLOW&pageNum=&pageSize=返回本人提醒页，含id/kind/actor公开资料/newsId/newsTitle/commentId/content/createdAt/read；PUT /community/notifications/read {kind,throughId}只标记本人该类别且ID<=已加载范围。信息入口包含私信、点赞、收藏、评论、新关注我的；记录页提醒与回复仅显示非零的提醒项，无内容时隐藏入口。前台5秒轮询，后台停止，不申请微信订阅推送。

真实导出复用data_export_task，V34新增client_request_id及data_export_file保存XLSX二进制。POST /api/v1/data-export-tasks {startDate,endDate,clientRequestId}创建本人任务，范围最多366天且不包含未来日期，幂等ID相同参数返回原任务；GET /data-export-tasks返回本人历史。GET /admin/v1/exports按状态/用户关键词分页；POST /exports/{id}/generate生成真实未删除血糖/饮食记录（每类最多20000条，超出明确失败，不截断）；完成文件为固定快照，重复生成已完成任务不改文件。POST /api/v1/data-export-tasks/{id}/generate仅本人；GET 两端对应/{id}/file下载，C端限定本人、后台需export:task:manage，文件成功二进制application/vnd.openxmlformats-officedocument.spreadsheetml.sheet、no-store，错误ApiResponse；仅已完成任务可下载，不返回无鉴权公开地址。无记录时生成只有表头的真实空工作簿，不制造示例。失败可重试、保留错误状态，后台不回退Mock。

GET /admin/v1/users及GET /admin/v1/users/{id}需user:manage，增加真实phone（可空），保留phoneMasked；详情返回用户公开/账号字段、状态、注册/活跃时间、真实记录数量和达标率，不返回密码/openid/session凭据。手机号未绑定时如实显示未绑定，不伪造微信手机号；健康档案仍走独立health:archive:view权限。

## 私信提醒、语音表情及文章配图（2026-10-20）

GET /api/v1/community/messages/unread 返回 {unreadCount:number,latestMessageId:string|null,latestSenderId:string|null}，只计当前有效用户收到的、发送人仍有效的未读消息，跨全部会话统计。小程序前台每5秒查询，后台暂停；在社区及我的私信入口显示非零角标，新消息显示不含正文的站内提示；正在阅读同一聊天时不重复提示，读到的消息按原throughId接口标记已读。无未读不显示0，不申请微信订阅消息。

私信语音复用POST /api/v1/speech-transcriptions：切换按住说话、松开识别、可修改/重录，确认后才调用原私信发送接口；识别文本和表情合计仍限1000字，发送失败保留草稿和幂等ID。

V33新增community_article_image(news_id,media_id,sort_no)。投稿/修改增加可选imageMediaIds:string[]，最多6张、顺序保留，不允许重复；图片走原上传凭证→PUT→登记，新增MediaBizType.ARTICLE_IMAGE=7，单张最多5MB，服务端验证当前用户所有、可用、未删除及图片MIME。文章列表/详情追加images:[{mediaId}]；旧文章为空。图片与正文/标签同事务保存，幂等比较包含图片及顺序，任何编辑重新初审与终审。

GET /api/v1/community/article-images/{mediaId} 为需登录的二进制图片接口（成功image/*，错误ApiResponse），只允许图片所有者，或已终审发布且未删除的文章所引用的图片；GET /admin/v1/community/articles/{id}/images/{mediaId} 需content:news:list并验证当前文章确实引用。两者不返回存储密钥/永久公开地址，Cache-Control:no-store；客户端带会话下载到临时文件，离页清理，管理员使用带鉴权的Blob。正文配图可预览/删除，按选择顺序展示于正文后；审核页同时展示配图并提醒管理员核验图片，现有AI初审仅审核文字，不宣称图片已通过AI审查。

## 评论回复与显示（2026-10-20）

V32在community_comment新增reply_to_comment_id及root_comment_id（旧顶级评论为空），不改已有正文或身份。POST /api/v1/news/{id}/comments增加可选replyToCommentId，服务端验证目标属于同一篇可见文章、评论和根评论可见，用户身份取会话；幂等标识同时校验正文与回复目标。支持回复评论或回复已有回复，统一归入最初的根评论。

GET /api/v1/news/{id}/comments继续按pageNum/pageSize查询顶级评论，total/pages表示顶级评论数量；每项追加rootCommentId、replyToCommentId、replyToAuthor、replies（首3条按ID正序）、replyCount、replyHasMore、replyNextAfterId。GET /api/v1/news/{id}/comments/{rootId}/replies?afterId=&pageSize=10返回records/hasMore/nextAfterId，验证文章、根评论及当前用户，不能跨文章读取。文章commentCount统计可见顶级评论与回复；根评论被删除或作者停用后整串不再公开，删除中间回复不删除其他人的回复。回复目标已删除时不泄露其正文或资料。

列表沿用author公开昵称/头像；小程序在正文下显示刚刚/N分钟前/N小时前/N天前，右侧为加粗回复入口。相对时间按服务端Asia/Shanghai无偏移时间解释，展示层不改服务端原始createdAt；私信等既有时间显示不变。

## 社区投稿、分类与审核（2026-10-20）

自定义标签补充：投稿/修改请求增加可选customTags:string[]，预置标签仍用tagCodes，合计去重后1～3个。自定义标签去掉前导#并规范空白，1～16个中文/字母/数字/空格/下划线/连字符；与已有标签同名时复用，按规范名称生成稳定code，同一名称并发创建不重复，停用标签不可绕过。写入文章、标签及关联同一事务，重试保留原请求标识。标签接口固定展示六个预置分类及已有已发布文章使用的自定义标签，未审投稿的新标签不提前出现在公共分类导航。按用户之前选择新增约14篇有来源的原创科普文章，六类均覆盖，先以AI_PENDING进入审核，publish_status=0，不直接公开，不伪造AI通过或终审记录。

初审队列默认启用（community.moderation.enabled可关闭），每次领取一个待处理版本，事务内锁定领取、事务外调用配置的文本模型；45秒调用超时、超过3分钟未完成的任务转为AI_ERROR，可显式重试。通过只到PENDING_ADMIN，拒绝到AI_REJECTED；结果必须与当前revision及AI_REVIEWING状态一致，旧版本或重复结果不回写，不修改公开状态。未配置可用文本模型、无效JSON或外部失败不自动通过，不回显密钥或上游原始错误。文章详情和列表中的已关联真实用户发布人统一为u{id}，官方编辑部保留b{id}。

管理端文章管理补充：GET /admin/v1/community/articles 默认列出所有未删除的官方资讯及社区投稿，不默认只看待终审。支持 status、source=OFFICIAL/COMMUNITY、keyword（标题或发布人昵称，最多80字）、pageNum/pageSize。列表与详情追加 source；author 返回发布人的公开昵称与社区号，不返回手机号、微信标识或密钥。官方旧文章按 cms_news.publish_status 映射 DRAFT/PUBLISHED/HIDDEN，投稿按真实审核状态展示；createdAt 回退到原文章创建时间。官方文章沿用 /admin/v1/news 编辑发布，社区投稿继续走 AI 初审及管理员终审，不可混用官方编辑接口绕过审核。

复用cms_news及点赞/收藏、community_follow、community_comment、community_message；迁移V31新增community_article、community_tag、community_article_tag、community_report、community_moderation_log，cms_news.community_managed标识需走审核的投稿。关注表已在V29建立，不另造重复表。文章为纯文本，标题1～80字、正文20～8000字、标签1～3个，标签包含饮食控糖、1型糖尿病、2型糖尿病、药物科普、运动管理、日常管理。

用户接口均需当前C端身份：GET /api/v1/community/tags；GET /community/articles（tag、following、keyword及pageNum/pageSize，路径均带/api/v1，下同）；POST /community/articles（title、body、tagCodes、clientRequestId）；GET /community/articles/mine；GET/PUT /community/articles/{id}（修改带revision）；POST /community/articles/{id}/retry-ai（仅AI_ERROR）；POST /community/reports（targetType=ARTICLE/COMMENT/USER/MESSAGE、targetId、reason、details、clientRequestId）。重试客户端标识不得变更正文，返回原记录；用户只能修改自己的文章，发布后修改立即撤下并重新审核，版本号防止覆盖。举报消息时必须是消息双方之一，不返回其他人的私信。

文章状态AI_PENDING→AI_REVIEWING→PENDING_ADMIN/AI_REJECTED/AI_ERROR，最终只有管理员通过→PUBLISHED；驳回REJECTED，下架HIDDEN。AI调用在数据库任务领取后进行，失败绝不自动放行，超时任务可回收；编辑版本变化后旧AI结果不能回写。AI只做初审，涉及保证治愈、个体处方剂量、明显危险建议/广告等给出拒绝建议；内容视为待检查数据，不能覆盖审核系统指令。管理员批准必须基于当前版本且初审通过，审核日志保留版本、意见及操作人。

运营接口GET /admin/v1/community/articles（status/pageNum/pageSize）、GET /articles/{id}、GET /reports要求content:news:list；POST /articles/{id}/review（revision、decision=APPROVE/REJECT/HIDE、note）、POST /articles/{id}/retry-ai、POST /reports/{id}/resolve（action=DISMISS/HIDE_ARTICLE/HIDE_COMMENT/RESOLVE、note）还要求管理员角色和content:news:edit。旧资讯编辑/发布/删除接口不得绕过投稿审核。公开资讯详情、互动及社区列表都必须验证投稿已终审发布；未发布内容仅作者与有权限运营可读。举报处理按目标精确下架文章/隐藏评论，不自动封禁用户或删除私信。

每篇文章的tags返回code/name；社区按标签或关注作者筛选并服务端分页，关注作者使用唯一公开社区号u{id}。预置约14篇原创科普示例，保留参考来源、标注为示例，采用唯一seed_key幂等导入；按用户选择先待审核，绝不冒充管理员已终审或已公开。

本文件是 `xiaoyejinjier/GlucoPilot-backend` 的权威契约。本文历史路径中的 `frontend/` 指独立仓库 `GlucoPilot-frontend` 根目录，`admin/` 指 `GlucoPilot-admin` 根目录，`backend/` 指本仓库根目录；拆仓不改变接口路径或响应格式。

## 双账号身份与私信发现（2026-10-19）

`GET /users/me`新增`communityKey:string`，固定为`u{当前C端userId}`；公开社区号用于复制、精确查找对方，不是登录凭据。小程序“找用户”输入社区号时调用已有`GET /community/profiles/{key}`，输入昵称仍走原搜索接口；不接收客户端伪造的senderId。私信列表首屏及聊天页前台每5秒刷新，离页停止。

`POST /auth/wechat/sessions`请求/响应保持不变。新增`wechat.login.mode=auto|wechat|mock`：auto有完整AppID/AppSecret时使用微信服务端code兑换，缺配置仅在dev/test且非prod时沿用原Mock；wechat模式缺配置明确拒绝登录，不退回模拟身份；mock模式只允许dev/test且非prod，生产禁止Mock。配置`WECHAT_APP_ID`/`WECHAT_APP_SECRET`仅在服务端；两个微信的真实openid对应不同用户，同一微信换code仍复用原账号。openid/unionid/session_key不返回小程序，不记录凭证、完整URL或微信响应；超时不自动重用code重试。

本次不迁移、合并或覆盖已有Mock用户与健康数据；真实登录会按真实openid查找/创建账号。手机实际登录须使用同一个已配置的小程序AppID；测试号/无AppSecret不能当成已通过真实微信身份验证。手机号当前仍沿用验证码日志实现，本次不伪装已接入短信网关。

## 社区互动与用户私信（2026-10-19）

继续使用现有资讯，不增加用户发帖。V29增加发布者、关注、评论和用户私信表，cms_news增加blogger_id默认1；旧资讯统一归属“社区编辑部”，不把后台created_by推断为C端身份。公开资料key为b{发布者ID}或u{用户ID}；资料只返回key/userId（可空）/nickname/avatarUrl/bio/followed/isSelf，不返回手机号、openid、健康资料。发布者仅在显式关联有效C端user_id时可接收私信；评论者可关注并发私信。不可关注自己，不可给自己发私信，禁用/注销/删除账号不可互动或被检索。

除原有公开资讯列表/详情外，下列接口全部要求C端登录，统一ApiResponse及X-Request-Id；pageNum从1开始、pageSize默认20且最多50，列表统一PageResult。详情增加author公开资料和commentCount；PUT /news/{id}/actions沿用liked/collected布尔目标状态，文章行锁保证并发计数不丢失。

| 方法与路径（均在/api/v1下） | 请求/响应 |
| --- | --- |
| GET /community/profiles?keyword= | 昵称精确/包含搜索，至少2字、最多32字；只搜有效用户公开资料，不按手机号检索 |
| GET /community/profiles/{key} | 公开资料、关注状态、是否本人 |
| PUT /community/profiles/{key}/following | `{following:boolean}`；幂等设置关注状态，返回最新资料 |
| GET /community/following | 本人关注的有效发布者/用户资料列表 |
| GET /community/collections | 本人收藏且仍已发布、未删除的资讯列表，字段兼容news列表 |
| GET /news/{id}/comments | 最新在前的评论页，字段id/content/createdAt/author/mine；仅已发布资讯 |
| POST /news/{id}/comments | `{content,clientRequestId}`，去空白后1～500字；客户端生成8～64位字母数字/横线/下划线幂等ID；同用户同ID同参数重复提交返回原评论，不同参数40900 |
| DELETE /news/{id}/comments/{commentId} | 只允许本人删除，重复删除成功；他人评论40400，逻辑删除 |
| GET /community/messages | 本人的私信会话列表：peer公开资料、lastMessage、unreadCount；按最新消息ID倒序 |
| GET /community/messages/{peerUserId} | 本人与指定用户的消息页，beforeId可选独占游标、每页最多50，页内时间正序；`{records,hasMore,nextBeforeId}` |
| POST /community/messages/{peerUserId} | `{content,clientRequestId}`，去空白后1～1000字；唯一键(sender_id,client_request_id)防重复，重复同参数返回原消息，不同接收者/正文40900 |
| PUT /community/messages/{peerUserId}/read | `{throughId:string}`；只标记该对方发给本人的且id<=throughId的消息已读，不把晚到消息误标已读 |

私信仅文本，普通HTTPS请求；打开页面每5秒刷新，退后台/离页停止；发送失败保留原文及幂等ID供重试，不自动重发，不显示虚假成功。所有查询以登录身份限定发送/接收双方，客户端不能指定senderId；第三人不可读、不可标已读。消息/评论正文不进入访问日志，不把私信内容放系统通知或公共资料；未知异常仍50000，参数40000、失效目标40400、幂等冲突40900。读取分页与标已读幂等；新增写请求以body.clientRequestId作幂等键，无需另外X-Idempotency-Key。

本次仅编写迁移与测试，不对真实数据库执行DDL。需配套后端构建、V29正常迁移后与两个不同C端账号真机验收；dev固定openid时两个客户端会是同一用户，不能用作双账号验收。

## 接口路由错误（2026-10-19）

通过鉴权后，未注册的接口返回 HTTP 404 / code=40400，message 为“接口不存在，请确认后端版本与请求地址”；已有路径不支持所用 HTTP 方法时返回 HTTP 405 / code=40500，并保留 Allow 响应头。两者继续使用 ApiResponse 和 requestId，不再落入 50000“系统异常”。业务资源不存在仍沿用原有 40400 语义；真实未处理异常仍返回 50000。

## 小语语音转文字草稿（2026-10-16）

`POST /api/v1/speech-transcriptions`：C端登录，普通JSON响应`ApiResponse<{text:string}>`，沿用requestId。请求为`{audioBase64:string,format:"mp3",durationMs:number}`，只接受纯Base64（不带data URI前缀）、MP3（含文件头校验）、原始文件最多512KiB；durationMs为500～60000整数，小程序录音强制60秒停止。Base64最长699052字符。

此接口仅转写，不创建会话、不写聊天消息、不保存音频。小程序展示可编辑的文字，必须由用户明确点击“确认发送”，才调用已有2.24文本发送和2.25流式回复（msgType=1），不使用尚无ASR补全支持的msgType=3。录音前已有输入草稿保留，用户确认时合并发送；取消、重录、离页、登录或会话变化均不得自动发送或回写旧结果。

错误：40000格式/大小/时长不合法，40001未识别到可用文字，40100未登录，42901本人识别仍在进行或服务繁忙，50000配置缺失/上游错误/超时。上游响应、凭据和音频不进入日志或对外错误。请求不可自动重试，无业务写入，不要求X-Idempotency-Key；同一用户仅允许一条在途识别，避免连点重复调用。

识别走服务端百炼Qwen3-ASR-Flash兼容接口，默认`ai.speech.model=qwen3-asr-flash`、baseUrl为百炼北京兼容端点；`AI_SPEECH_API_KEY`独立配置，缺省复用`ai.vision.api-key`（本地yaml或环境变量已配置的百炼密钥）。不把密钥放小程序，也不依赖OSS上传或微信第三方插件。语音服务缺配置只影响此接口，不阻止应用启动。前端须声明麦克风用途并处理拒绝授权、录音中断、无语音和识别失败。

## 设备添加准备（2026-10-15，尚未接入真实设备）

用户确认尚无具体设备型号。本次仅增加小程序页面、微信原生BLE发现、扫码/SN临时登记，不增加HTTP接口或后端DTO。登记数据仅保留在本次登录会话内存，统一标记“待适配”，不等于验证、绑定或连接成功；不写入glucose_record，不产生模拟血糖。

后续须确认厂商型号、二维码/SN规则、BLE服务/特征UUID、授权配对、读数解码与单位/时间约定，再由接口负责人确认绑定和读数入库契约。不得把任意附近蓝牙设备当成已支持血糖仪，不得用名称或SN猜测读数；二维码只提取SN文本，绝不自动请求或打开其链接。

## 高低血糖异常事件中心（2026-10-15）

### 给用户的回复

内部 `note` 仍只在管理端可见；新增 `userReply`（去空白后最多1000字），独立持久化到case与log（迁移 `V17__glucose_alert_user_reply.sql`），case另存 `repliedAt`。处理请求中省略/null保留回复，空字符串撤回，非空发布；历史内部备注不自动公开。

管理端列表/详情返回 `userReply/repliedAt`，日志返回当次操作后的 `userReply`。回复改变才更新 `repliedAt`（撤回时置空）；仅改内部备注不改变回复时间。幂等及版本校验包含回复。已处理/已关闭事件可在状态和内部备注不变时补改公开回复，不得重开，追加审计日志。

`GET /api/v1/glucose-alerts/replies?pageNum=1&pageSize=3`：C端登录，只查询当前用户的非空回复，按回复时间及case ID倒序，使用统一PageResult。调用方不得指定userId。白名单字段为 `{ id, recordId, recordDate, nodeCode, nodeName, glucoseValue, recordedAt, userReply, repliedAt }`，id为case ID；不返回内部备注、日志、操作人、手机号或他人数据。原记录/账号删除后不公开；原测量更正后，旧回复保留并明确关联旧快照，不附着到新测量。

用户端仅提供紧凑消息入口，由用户点击打开提醒/回复弹窗，支持刷新、分页和重试。退出页面、账号变化和晚到请求不得泄漏旧账号回复；原录入、拍照、曲线和统计不变，无用户写操作或强制弹窗。

### 异常事件查询及处理

- 规则版本 `glucose-alert-v1`：有效实测 `(0,40)` 中 `<3.9` 为LOW，`>=10.0` 为HIGH，单位mmol/L。这是产品提醒阈值，不替代个人诊疗目标；缺失值和餐食预测不生成提醒，不改变原图表/达标率。
- 来源为未删除的 `glucose_record` 六个非餐点节点，排除删除账号。当前事件id为血糖记录ID字符串，默认PENDING；运营跟进保存到 `gp_glucose_alert_case`，追加审计到 `gp_glucose_alert_log`（迁移V16），不改历史血糖。
- `glucoseValue + recordedAt` 构成测量快照；重新测量或改值后新快照待处理，旧跟进不得覆盖新测量。原记录恢复正常或删除后退出当前异常列表，日志保留。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /admin/v1/glucose-alerts` | `health:alert:view` | 参数 `startDate/endDate`（默认近30天，最多92天）、`keyword`（用户名/昵称，最多64字）、`alertType`（LOW/HIGH）、`status`、`pageNum/pageSize`；返回 `{ page, overview, lowThreshold, highThreshold }`，overview统计整个筛选范围而非当前页 |
| `GET /admin/v1/glucose-alerts/{id}` | `health:alert:view` | 返回 `{ event, history }`，包括当前真实血糖、原备注、脱敏手机号和全部快照处理日志 |
| `PUT /admin/v1/glucose-alerts/{id}/handling` | `health:alert:handle` | 请求 `{ glucoseValue, recordedAt, expectedVersion, status, note, userReply? }`；快照必填、版本从0开始、note去空白后1～1000字，返回当前事件 |

状态：`PENDING → PROCESSING/RESOLVED/CLOSED`，`PROCESSING → PROCESSING/RESOLVED/CLOSED`；终态不得重开。RESOLVED仅表示运营跟进完成，不表示血糖恢复。记录操作人、时间、状态、快照与requestId；行锁及expectedVersion防止覆盖。相同操作人重复提交相同状态/备注/回复幂等返回，不增日志；过期快照/版本冲突返回40900提示刷新。PUT按状态和版本保证幂等，不依赖随机客户端key。

查看权限继承已有健康档案查看角色，处理权限默认管理员；运维可另行分配。所有响应继续使用 `ApiResponse { code, message, data, requestId }`、成功码0、保留X-Request-Id，鉴权使用现有机制。不提供改删原血糖或用药剂量功能。

## 官方若依后端迁移约定（2026-10-09）

后端改为官方 RuoYi-Vue 多模块工程，由 `ruoyi-admin` 启动。所有原有路径、普通响应成功码 0、分页字段和 SSE 事件继续保持。管理端登录返回的 accessToken 改为若依 JWT，登录状态由 Redis 管理；旧后台会话在切换时失效，小程序 user_session 保留。运营身份与 sys_user 通过 gp_ops_identity_map 关联，既有 opsId 和审计字段语义保留。系统资源适配接口调用官方系统服务，业务分组参数独立保存于 gp_app_config。

管理端在线会话接口的 id 改为若依会话 UUID 字符串（客户端仅用于显示与强退路径）；其他字段继续保持。强退请求仍为 DELETE /admin/v1/ruoyi/online/{id}。账号禁用、密码和权限变更必须撤销或刷新受影响的 Redis 会话。新建运营账号写入若依账号并建立业务身份映射。

## 管理端 C 端用户管理

管理端“用户管理”页面必须读取业务库中的 C 端用户，不得回退到前端 Mock 数据，也不得与若依运营账号 `/admin/v1/ruoyi/users` 混用。

| 方法与路径 | 权限 | 数据来源 | 说明 |
| --- | --- | --- | --- |
| `GET /admin/v1/users?pageNum=1&pageSize=100&keyword=&accountStatus=` | `user:manage` | `user_account`，关联 `user_profile`、`user_health`、`glucose_record` | 分页查询 C 端用户；`accountStatus` 可传 `0`（禁用）、`1`（正常）、`2`（注销中） |
| `PUT /admin/v1/users/{id}/status` | `user:manage` | `user_account.account_status` | 请求体为 `{ "accountStatus": 0 }` 或 `{ "accountStatus": 1 }`，用于禁用或解禁 |
| `GET /admin/v1/users/{id}/archive` | `health:archive:view` | `user_account` / `user_profile` / `user_health` / `glucose_record`（+ `meal_record` 汇总） | 健康档案详情，供管理端「健康档案」页替换 Mock 数据 |

列表返回统一的 `PageResult`，字段为 `pageNum`、`pageSize`、`total`、`pages`、`records`。每条用户记录同时返回账号表的 `username` 与资料表的 `nickname`；用户手机号仅返回脱敏值；血糖记录数与达标率取自未删除的 `glucose_record`，`mealCount` 取自未删除的 `meal_record`，不得用 Mock 数量冒充数据库数据。

档案详情 `GET /admin/v1/users/{id}/archive` 返回 `data`：`userId`、`owner`（`nickname` / `phoneMasked` 脱敏 / `age` / `diabetesTypeText` / `registeredAt`）、`profile`（`height` / `weight` / `medication` / `exerciseHabit` / `diagnosedYm`）、`trend`（近 7 日 `{date, avg, okRate}`，仅含有记录的天）、`nodeStats`（各血糖节点 `{node, nodeName, avg, samples}`，节点名取自 `dict_glucose_node`）、`mealSummary`（近 30 日 `{recordDays, avgCarbsG, avgCal, topFoods}`）与 `summary`。
**窗口口径已冻结：趋势 = 近 7 日、饮食汇总 = 近 30 日**（口径常量为 `AdminUserService` 内的 `TREND_DAYS` / `MEAL_SUMMARY_DAYS`）；变更需同步本契约与 `AdminUserServiceTest`。达标率口径为 `[3.9, 7.8)`，与用户列表 `complianceRate` 同源。
其中 `profile.height` / `profile.weight` 取自 `user_health`（`profile.health` 域，C 端 `PUT /users/me/health-profile` 写入，未填写返回 `null`），`summary`（AI 档案小结无数据源）**暂返回 `null`，为 TODO**，前端按空值展示；用户不存在时按公共 `40400` 处理。

以上接口全部返回公共 `ApiResponse { code, message, data, requestId }`。成功码为 `0`；非法状态使用公共 `40000`；用户不存在使用公共 `40400`；鉴权和系统异常继续由 `GlobalExceptionHandler` 按公共 `ErrorCode` 处理，禁止自定义另一套响应码。

## 管理端九时段记录可视化

管理端通过 `GET /admin/v1/health-records/nine-slot` 查看 C 端用户真实入库的九时段数据，权限复用 `health:archive:view`。查询参数为 `startDate`、`endDate`、`keyword`、`pageNum`、`pageSize`；日期为空时默认最近 30 天（含当天），单次查询最多 92 天，`keyword` 匹配用户名、昵称或手机号。

接口按“用户 + 业务日期”分页，返回 `overview` 与 `page`：

- `overview` 包含 `activeUsers`、`recordDays`、`glucoseRecords`、`mealRecords`、`totalRecords`、`avgGlucose`、`complianceRate`、`abnormalRecords`、`trend` 和 `nodeStats`；
- `page` 使用统一 `PageResult`，每条记录包含用户、日期、完成数、实测血糖均值以及 `slots`；`slots` 固定覆盖九个节点；
- 六个非餐点节点读取 `glucose_record`，返回实测 `glucoseValue`、来源、备注和记录时间；
- 早餐、中餐、晚餐读取 `meal_record`，返回食物摘要、碳水、预测值和记录时间；正餐预测值只能作为饮食记录详情展示，禁止计入 `avgGlucose`、达标率或真实血糖趋势；
- 达标口径与现有用户管理一致：实测血糖 `[3.9, 7.8)` 为达标，其他实测值计入异常记录。

接口只读，不提供后台修改或删除用户健康记录的能力。响应继续使用统一 `ApiResponse { code, message, data, requestId }`，成功码为 `0`。

## 管理端业务列表分页

用户、食品和反馈三个管理列表统一使用服务端分页，响应 `data` 均为 `PageResult { pageNum, pageSize, total, pages, records }`。`pageNum` 从 1 开始，`pageSize` 范围为 1～100；筛选条件由服务端执行，管理端不得先拉取固定数量的数据再进行本地分页。

| 方法与路径 | 可选筛选参数 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET /admin/v1/users` | `keyword`、`accountStatus`、`pageNum`、`pageSize` | `user:manage` | C 端用户分页；关键词匹配用户名、昵称或手机号 |
| `GET /admin/v1/foods` | `keyword`、`categoryId`、`enabled`、`pageNum`、`pageSize` | `food:library:manage` | 食品分页；`enabled=true/false` 对应上架/下架 |
| `GET /admin/v1/feedbacks` | `keyword`、`feedbackType`、`replyStatus`、`pageNum`、`pageSize` | `feedback:manage` | 反馈分页；类型为 `1～4`，处理状态为 `0～3` |

运营端反馈列表按反馈 `id` 从小到大排序后执行分页，先提交的记录排在前面。页面只显示一列连续“序号”，计算方式为 `(pageNum - 1) * pageSize + 行索引 + 1`，翻页后继续编号；数据库 `id` 保留在接口中用于审批/回复，不另外显示为列表编号。

管理端食品营养库按表格实际可用高度及行高自动计算每页条数（范围1～100），不固定22条；窗口/容器尺寸变化后重算容量并回到第一页，相同容量不重复请求。分页栏保留在表格下方，支持切换页码或跳页。每次翻页均调用 `/admin/v1/foods` 获取服务端分页结果，禁止对旧数组响应进行本地切片以冒充服务端分页。关键词、分类与上下架筛选提交后回到第一页，后续翻页保留已提交的筛选条件；新增食品回到第一页，删除或下架导致当前页超出总页数时自动回到最后一个有效页。

反馈管理、用户管理和九时段用户每日明细使用同样的分页交互：默认 10 条、可选 10/20/50/100 条，表格内部滚动，分页栏展示总数、当前页和记录区间。页码与每页条数改变时重新请求各自服务端接口，筛选条件只在提交查询时生效并随翻页保留；反馈状态、用户状态变更或数据减少导致当前页越界时自动回退。九时段按“用户 + 日期”分页，`overview` 统计整个查询范围，不按当前页记录数计算，也不得随翻页丢失已提交的日期范围。

## 管理端内容媒体（资讯图 / 轮播图 / 资讯视频）

媒体素材页管理资讯配图、轮播图与资讯视频：走对象存储「凭证 → PUT → 登记」。开发可用 `STORAGE_PROVIDER=local` 或 `minio`；**部署默认 MinIO**（不默认阿里云 OSS）。稳定地址由服务端读私有对象，不把预签名 URL 写入 CMS 表。

| purpose | MIME | 大小上限 | biz_id | 稳定公开 URL |
| --- | --- | --- | --- | --- |
| `news` / `banner` | `image/jpeg`、`image/png` | 5MB | `CONTENT_NEWS` / `CONTENT_BANNER` | `/api/v1/cms/images/{id}` |
| `news-video` | `video/mp4` | 100MB | `CONTENT_NEWS_VIDEO` | `/api/v1/cms/videos/{id}` |

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /admin/v1/cms-media?purpose=` | `content:media:manage` | `purpose` 可选 `news` / `banner` / `news-video` / 省略（全部） |
| `POST /admin/v1/cms-media/upload-credentials` | `content:media:manage` | 请求 `{ purpose, bizType: 5, mimeType, sizeBytes }` |
| `POST /admin/v1/cms-media` | `content:media:manage` | 请求 `{ purpose, bizType: 5, storageKey, mimeType, sizeBytes }`；建议带 `X-Idempotency-Key` |
| `DELETE /admin/v1/cms-media/{id}` | `content:media:manage` | 软删除（`file_status=0`），仅本人运营账号上传的素材 |
| `GET /api/v1/cms/images/{id}` | 公开 | 返回已登记 CMS 图片二进制 |
| `GET /api/v1/cms/videos/{id}` | 公开 | 返回已登记 CMS 视频（MP4）二进制 |

`media_file` 使用 `biz_type=CMS(5)`，`ops_id` 为当前运营，`user_id` 为空。Banner `image` / 资讯 `coverUrl` / `videoUrl` 填上述稳定相对路径。资讯正文 `contentBody` 为 **HTML**（管理端富文本）；小程序详情用 `rich-text` 渲染，正文插图使用 `/api/v1/cms/images/{id}`。

## 管理端食物图片

待审核新食品（`nutritionSource=AI_ESTIMATE`且未上架）优先使用用户本次上传的餐食实拍图作为审核预览。识别服务只关联已验证归属的MEAL媒体，新增`food_nutrition.source_media_id`（迁移V30）；新草稿及缺少实拍关联的旧草稿可补充首次来源，重复识别不覆盖已关联照片，也不改动已审核或人工食品。管理端食品DTO新增只读`reviewImage`：有来源且仍待审核时为`/admin/v1/foods/{id}/review-image`，否则为空。该字段只用于审核，不写入公开`image`或食品库配图。

`GET /admin/v1/foods/{id}/review-image`要求运营登录及`food:library:manage`权限，验证食品仍待审核、来源媒体未删除/可用且为餐食图片后，返回原图二进制，使用`Cache-Control: no-store`；缺失或失效返回统一404响应。管理端携带Bearer读取为Blob预览，离页/换食品回收临时URL；照片缺失或加载失败时回落已有配图，再回落内置图片。用户健康照片不经公开食品配图接口发布。旧草稿没有来源关联时继续显示原配图，下次同名高置信度识别时可补充来源。

新增与编辑食物可选择内置图片或已上传的食物图片，也可上传新的 JPG/PNG 图片（单张 1 字节至 5MB）。上传采用现有对象存储的“凭证 → PUT 文件字节 → 登记”流程，不将 base64 或浏览器临时地址写入食品表。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /admin/v1/food-images` | `food:library:manage` | 查询已登记、可用的食物图片，返回 `{ id, fileUrl }[]` |
| `POST /admin/v1/food-images/upload-credentials` | `food:library:manage` | 请求 `{ bizType: 5, mimeType, sizeBytes }`；返回 `{ uploadUrl, storageKey, expireIn }` |
| `POST /admin/v1/food-images` | `food:library:manage` | 请求 `{ bizType: 5, storageKey, mimeType, sizeBytes }`；返回 `{ id, fileUrl }`；携带 `X-Idempotency-Key`，相同对象重复登记复用原记录 |

食物图片使用 `media_file` 的 `CMS(5)` 类型、`biz_id=FOOD_IMAGE` 和当前运营 `ops_id`，`user_id` 为空。服务端验证对象所属运营账号、文件大小、真实图片格式与尺寸，禁止登记其他用途图片。图片稳定地址为 `/api/v1/foods/images/{id}`；该地址公开读取经过登记的食物配图，成功返回图片二进制（与已有文件读取接口一致），失败返回统一 `ApiResponse`。私有对象存储通过服务端读取，不将短期签名下载地址保存到 `food_nutrition.image_url`。小程序从统一地址配置解析此相对路径。

管理端食品大类也可指定图片：`GET /admin/v1/food-categories` 返回 `{ id, name, sort, image }`；分类新增与编辑请求支持 `{ name, sort, image }`，`image` 为可选字段、最多 512 字符。图片地址持久化到 `food_category.image_url`（新增迁移 `V14__add_food_category_image.sql`）；旧分类没有图片时，由管理端根据分类名称展示内置图片。旧调用方省略 `image` 时保留原分类图片；传空字符串时清空自选图片并使用默认图。选择已有图片和上传新的 JPG/PNG 图片复用上述食物图片接口，分类列表与分类选择下拉框展示保存后的图片。

分类保存成功响应必须使用数据库回读结果；管理端保存后再次读取分类列表，确认目标分类的 `image` 与提交值一致才提示成功。后端仍返回旧结构、漏存图片或列表读取失败时，保留编辑弹窗与已上传图片并显示具体错误，禁止提示保存成功后悄悄退回默认图。

九时段用户每日明细默认 10 条全部展开，分页栏置于这 10 条下方；每页选择更多条数时才限制表格高度并启用内部滚动。

## 免责声明版本门禁

用户登录或小程序从后台重新进入前台时，客户端读取 `/api/v1/users/me` 中的 `settings.disclaimerAgreed`、`settings.disclaimerVersion`，并与公开配置 `/api/v1/app-config?groups=TEXT` 返回的 `text.disclaimer.version` 比较。未同意，或已同意版本与当前版本不一致时，必须通过 `reLaunch` 进入免责声明页，阅读到底并成功提交同意前不得进入业务页面；已同意当前版本的用户正常进入首页，不重复打扰。

更新免责声明时，运维必须同时更新 `text.disclaimer` 与 `text.disclaimer.version`。用户通过 `POST /api/v1/users/me/settings/disclaimer-agreement` 提交当前版本，服务端再次校验版本并记录 `disclaimer_agreed`、`disclaimer_version`、`disclaimer_agreed_at`；版本不一致时拒绝提交并要求重新读取最新文案。

## 统一入口

| 调用方 | 路径前缀 | 责任边界 |
| --- | --- | --- |
| 用户端小程序 | `/api/v1/**` | 登录、内容、营养和设备血糖同步 |
| 管理后台 | `/admin/v1/**` | 运营账号、资讯、Banner 和营养库管理 |
| 运维探针 | `/actuator/health` | 存活检查，不提供业务数据 |
| 运营权限上下文 | `/admin/v1/system/user-info`、`/admin/v1/system/menus` | 当前账号的角色、权限标识与可见菜单 |
| 若依系统管理 | `/admin/v1/ruoyi/**` | 用户、角色、菜单、部门、岗位、字典、参数、公告、日志、在线会话、任务与代码生成 |

生产环境只有 Nginx 对外开放。Nginx 将前两类接口转发给后端容器，管理端静态资源也由同一个入口提供；调用方不得绕过网关访问 `8080`。

## 响应格式

所有业务接口均返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "d7b3..."
}
```

- `code = 0` 表示成功；非零值由前端作为业务失败处理。
- `requestId` 由网关或后端生成，并贯穿后端日志；反馈问题时必须携带它。
- `Authorization: Bearer <token>` 用于受保护接口。
- `X-Idempotency-Key` 用于可能被重试的写操作；同一业务动作不得生成多个不同 key。

## 管理端权限

管理端使用 RuoYi 的权限表达式约定：后端以 `@PreAuthorize("@ss.hasPermi('资源:动作:范围')")` 保护业务操作，权限数据来自 `sys_role`、`sys_ops_role`、`sys_permission` 和 `sys_role_permission`。登录响应以及 `/admin/v1/system/user-info` 均返回当前账号的 `roles`、`permissions` 与 `menus`；前端只能用它们控制可见性，不能替代后端鉴权。

## 完整若依模块

系统管理接口统一位于 `/admin/v1/ruoyi/**`，保持项目的版本前缀和 `ApiResponse` 响应格式，不能直接暴露官方项目的无版本路径。该区域包括：

| 资源 | 路径 | 权限标识 |
| --- | --- | --- |
| 运营用户 / 角色 / 菜单 | `/users`、`/roles`、`/menus` | `system:user:manage`、`system:role:manage`、`system:menu:manage` |
| 部门 / 岗位 | `/depts`、`/posts` | `system:dept:manage`、`system:post:manage` |
| 字典 / 参数 / 公告 | `/dict-types`、`/dict-data`、`/configs`、`/notices` | `system:dict:manage`、`system:config:manage`、`system:notice:manage` |
| 监控与审计 | `/online`、`/login-logs`、`/oper-logs`、`/monitor/server` | `monitor:online:list`、`monitor:logininfor:list`、`monitor:operlog:list`、`monitor:server:list` |
| 定时任务与生成器 | `/jobs`、`/generator/tables` | `monitor:job:manage`、`tool:gen:manage` |

所有写操作都必须携带现有的运营会话令牌；在生产调用时还应传入稳定的 `X-Idempotency-Key`。任务只接受平台内置、白名单化的任务键，禁止传入任意类名或脚本作为调度命令。

## 用户反馈闭环

用户端与管理端共享 `feedback` 数据源，接口职责如下：

| 调用方 | 方法与路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| 用户端 | `POST /api/v1/feedbacks` | C 端登录 | 提交反馈，初始状态为 `0`（已收到） |
| 用户端 | `GET /api/v1/feedbacks` | C 端登录 | 只查询当前用户反馈及官方回复 |
| 管理端 | `GET /admin/v1/feedbacks` | `feedback:manage` | 分页查询全部用户反馈 |
| 管理端 | `PUT /admin/v1/feedbacks/{id}/reply` | `feedback:reply` | 更新处理状态与官方回复 |

处理状态统一为：`0` 已收到、`1` 处理中、`2` 已处理、`3` 已关闭。管理端把状态改为“已处理”时必须填写官方回复；回复写入 `official_reply` 后，用户端再次进入“我的反馈”即可通过现有查询接口看到结果。该 PUT 接口按反馈 ID 覆盖当前处理结果，重复提交相同内容不会创建新记录。

## 设备数据同步

用户端通过 `POST /api/v1/device/glucose-sync` 将血糖记录提交到同步服务。请求必须使用已登录用户的令牌，记录应带来源侧稳定的 `externalRecordId` 以便追踪重试来源；当前服务按用户、业务日期和血糖节点执行写入或更新，重复同步同一节点不会新增记录。接口修改前必须同步更新用户端调用、服务端 DTO 和测试。

## 九时段统计

2026-10-14 记录页今日曲线交互：固定保留九时段横轴位置，仅非餐点的有效实测血糖参与折线和Y轴范围。点击实心点高亮该点，并在曲线下方展示时段、血糖、真实记录时间、来源和备注；点击空心占位点只提示无实测数据，空白处取消选择，不修改记录、不受录入截止限制。使用Canvas本地坐标进行扩大命中判断，滚动/多指操作不当作点击；不使用遮盖Canvas的弹窗。

沿用 `GET /api/v1/day-boards/{recordDate}` 与 `GET /api/v1/meal-records` 同步当日看板，同时调用既有 `GET /api/v1/glucose-records?startDate=yyyy-MM-dd&endDate=yyyy-MM-dd&pageSize=100` 获取真实 `recordedAt/sourceType/remark`。血糖记录列表成功时优先使用其完整快照；列表失败时可使用看板值，但只在缓存血糖值一致时保留旧记录时间，否则显示“时间未提供”，不得用当前时刻补齐。旧同步/绘制回调在离开页面或切换日期后须失效；无服务端DTO变更。

2026-10-14 日历视图层级调整：`month` 模式显示所选年份的1～12月入口，点击月份切换至 `day` 模式。`day` 模式展示该月完整日期日历，原完成度着色及选中日期高亮保留；日历下方同时显示选中日期的九时段明细、完成率及实测均糖，不再以日明细替换日期日历。支持切换年份、月份、相邻日期及返回月份列表。月份入口只表示导航，不伪造全年统计、不额外添加年度接口；日期日历沿用统计接口，日明细并行调用血糖记录与饮食记录接口，加载/错误状态独立，快速切换时不得显示旧日期数据。选择月份或日期仍清除手动选周覆盖，血糖周视图默认跟随该日期。

用户端时段选择与拍照、确认保存共用截止规则（设备当地时间）：10:00 截止早餐前与早餐；14:00 截止早餐后、午餐前与午餐；20:00 截止午餐后、晚餐前与晚餐；晚餐后不设日内截止。截止时间点本身视为已截止，页面回到前台、点击节点和确认保存时均再次校验。该规则限制当天录入入口，不影响已保存记录的查询与后台展示。

用户端通过 `GET /api/v1/glucose-records/summary` 获取统计页数据。每天的完成度按九时段计算：六个非餐点节点读取 `glucose_record`，早餐、中餐、晚餐三个餐点节点读取 `meal_record`；同一日期同一节点最多计一次，因此单日完成数范围为 `0-9`。正餐是否由拍照识别生成不影响统计，只要该餐点已有有效饮食记录即视为完成。

统计页周曲线必须传入当前选中日期所在自然周的 `startDate` 与 `endDate`（周一至周日），不能只传 `year/month` 后始终展示服务器当前周。曲线按每日实测血糖均值绘制；正餐记录不作为血糖值，无记录日期保留横轴占位，已有数值点按日期顺序连接。

2026-10-14 血糖视图支持独立选周：默认跟随日历/月视图选中日期；点击曲线卡片中的周日期范围弹出选周面板，按月份列出与该月相交的完整自然周（允许跨月、跨年），可切换月份并选择。手动选周仅替换请求中的 `startDate/endDate`，`year/month` 仍对应原日历月份，不改变日历选中日期；月度指标与时段分布仍使用原日历月份。后续改变日历日期/月份或点击“跟随日历”时，清除手动周覆盖。周曲线和周摘要同步更新；快速切周时旧请求不得覆盖新选择，失败须显示重试状态而非把旧数据当成新周结果。沿用现有接口，无后端 DTO 变更。

响应中的 `calendar.days[].recordCount`、`monthStats.totalRecords`、`monthStats.recordDays`、`checkIn.todayCount`、`checkIn.streakDays` 和 `checkIn.monthRecordDays` 均按血糖与正餐记录合并后的九时段口径计算；`checkIn.totalNodes` 固定来自启用的九时段字典。`monthStats.avgValue/maxValue/minValue`、`week`、`slotStats` 和 `trend` 只统计具有真实数值的血糖记录，禁止把餐食预测值当成实测血糖。

`monthStats.complianceRate` 当前表示当月有任一时段记录的天数除以当月总天数，客户端必须展示为“记录率”或“打卡率”，不得展示为血糖达标率。

## 饮食记录

### 正餐照片识别与营养库匹配

确认照片后上传为 `MEAL(3)`，调用 `POST /api/v1/meal-analysis`，请求 `{ mediaId, nodeCode, foodText? }`。需要用户登录，校验照片归属和餐点节点，补充文字最多200字；携带稳定 `X-Idempotency-Key`，客户端超时90秒。取消/离开页面不写入饮食记录。

餐食场景复用千问视觉凭据，独立使用 `ai.meal.model`（`AI_MEAL_MODEL`，默认 `qwen3-vl-plus`），不改变血糖 OCR 模型。响应含 `model`、`items`、`carbsG`、`caloriesKcal`、`matchedCount`、`newFoodsCount`、`warnings`。明细含食物名、`foodId`（仅上架食品）、`libraryFoodId`、分类码、估算重量、每100g营养、按份量计算的营养、GI（库中已有时）、`nutritionSource`、`libraryStatus`、置信度。无法确定的数值返回空，客户端要求补充或重新识别，不得替换为示例值。

规范化食品名后精确匹配 `food_nutrition`，已有食品必须使用库中营养，严禁模型覆盖。可信的新食品自动入库为待审核条目（`shelf_status=0`、`nutrition_source=AI_ESTIMATE`），保留来源模型；使用现有唯一键防止重复登记。模型估算明确标记，运营核对营养后可编辑上架。待审核 ID 不作为上架 `foodId` 提交，饮食记录保存食物名和营养快照。迁移 `V15__add_food_nutrition_source.sql` 保存来源。

确认食物及份量后使用原饮食记录接口保存照片与明细。真实血糖预测必须提供真实当前血糖并调用现有 `/api/v1/predictions/meal-glucose`，未提供时不显示数值预测，仍可保存饮食。

2026-10-14 用户授权的临时演示：餐食结果页暂不调用真实预测接口，识别成功后自动展示“模拟预测评分”。前端适配入口为 `frontend/services/meal-score-preview.js` 的异步 `getMealScorePreview(input)`，入参包含餐点、食物明细、营养合计、可空实测血糖、饱腹感；返回 `{ schemaVersion: 1, source: 'MOCK', score: 85, maxScore: 100, label, description }`。85分是固定展示样例，不根据营养或血糖计算，不代表真实评估；食品识别、食品库匹配及营养数据仍为真实接口结果。修改补充信息、重新识别、识别失败或退出页面时清除失效评分。

模拟评分只保存在当前页面，不提交 `score`、`predictNow`、`predictSuggest` 或模拟建议，不写入本地饮食缓存或数据库、不参与统计。后续由预测模块负责人确认真实评分接口契约，再替换上述适配入口；现有后端预测实现和请求/响应字段不改动。

用户端确认餐食后写入饮食记录与营养快照；快照在保存时点固化、不可变，不提供字段级修改（改 = 重新保存覆盖），不随营养库实时联动。同日同餐点重复保存为覆盖写入，不新增记录。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/meal-records` | C 端登录 | 保存（覆盖写入）饮食记录；`nodeCode` 须为餐点节点（`breakfast`/`lunch`/`dinner`） |
| `GET /api/v1/meal-records?recordDate=yyyy-MM-dd` | C 端登录 | 按日查询当前用户的饮食记录（含明细与照片 URL） |
| `DELETE /api/v1/meal-records/{id}` | C 端登录 | 逻辑删除饮食记录（含明细），仅限当前用户 |

保存请求体字段：`recordDate`（必填）、`nodeCode`（必填，餐点）、`mediaId`（餐食照片 ID，可空，须为当前用户 `MEAL` 类型）、`foodsText`（可空，≤512，为空时按明细食物名拼接兜底）、`carbsG`（可空，≥0，为空时按明细求和兜底）、`hungerLevel`（可空，`1` 饱腹 / `2` 一般 / `3` 微饿）、`supplementText`（可空，≤512）、`predictNow` / `predictSuggest`（可空，`(0,40)`）、`suggestText`（可空，≤512）、`items`（可空，≤50，每项含 `foodId` 可空、`foodName` 必填 ≤64、`weightG` / `carbsG` / `caloriesKcal` 可空 ≥0，`foodId` 非空时须为上架食物）。

预测三列 `predictNow` / `predictSuggest` / `suggestText` 由 prediction 客户端串联在用户确认后携带，服务端只校验范围与长度、不重算；两个模块未上线期间传空即可。删除后同日同餐点可重新保存。

## 餐后血糖预测

用户端通过 `POST /api/v1/predictions/meal-glucose` 获取餐后 1 小时血糖预测。服务端为纯计算：不落库、不查 `glucose_record` / `meal_record`，自身无持久化，可重复调用（重算 = 同接口携带补充信息重复调用）；唯一服务端读取是健康档案的分类参数（经 `profile.health` 服务接口自取，未建档时兜底建档 + 默认参数分档，正常返回）。食物数据由客户端从 ai 分析结果转发（ai 未上线时前端可手输联调），当前血糖由客户端传入，服务端不查血糖记录、不设默认值。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/predictions/meal-glucose` | C 端登录 | 餐后 1h 血糖两段式预测（现状 / 建议文案 / 按建议量再预测），`nodeCode` 须为餐点节点（`breakfast` / `lunch` / `dinner`） |

请求体字段：`nodeCode`（必填，餐点）、`currentGlucose`（必填，`(0,40)` mmol/L）、`carbsG`（必填，≥0）、`items`（可空，≤50，每项 `foodName` 必填 ≤64、`categoryCode` 必填 `1-9` 且**透传不落库**——前端从 food 库选食时详情自然带 `category_code` 填入、`weightG` / `carbsG` 可空 ≥0）、`portionRatio`（可空，`(0,2]`，默认 1.0）、`medicationTaken`（可空布尔，默认未服药）、`exerciseDone`（可空布尔，默认未运动）、补充原文（可空，≤200）。

响应 `data`：`predictNow`（现状预测）、`suggestText`（建议文案——`items` 非空走分食物建议，为空退回碳水换算，均不调 LLM）、`predictSuggest`（按建议量再预测）、`suggestCarbs`（推荐总碳水 g，1 位小数；危险高血糖场景为 0）、`suggestItems`（分食物结构化建议，与 `suggestText` 同源派生；每项 `foodName` + `suggestWeight`：数值=建议吃 Xg、`0`=不建议、`null`=保持原份量（正常）；整体降级/暂缓进食/无明细场景为空数组）、`algorithmVersion`（`"v1"`，算法数值/系数/文案变更时升级）。预测数值均收口到 `[0.1, 39.9]` 并保留 1 位小数。

| 场景 | code |
| --- | --- |
| `nodeCode` 缺失/空白、`currentGlucose` 缺失或 ∉ `(0,40)`、`carbsG` 缺失或为负、补充标签非法值、`items` 要素非法（`foodName` 缺失、`categoryCode` 缺失或 ∉ `1-9`、数值越界） | `40000` BAD_REQUEST |
| `nodeCode` ∉ 餐点节点 | `40001` BIZ_REJECTED |
| 未建档（首次调用触发兜底建档 + 默认参数，正常预测） | `0` |
| 未登录 / 令牌失效 | `40100` UNAUTHORIZED |
| 系统异常 | `50000` INTERNAL_ERROR |

出参示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "predictNow": 8.4,
    "suggestText": "米饭 150g · 红烧肉正常 · 青菜正常",
    "predictSuggest": 7.8,
    "suggestCarbs": 45.0,
    "suggestItems": [
      { "foodName": "米饭", "suggestWeight": 150 },
      { "foodName": "红烧肉", "suggestWeight": null },
      { "foodName": "青菜", "suggestWeight": null }
    ],
    "algorithmVersion": "v1"
  },
  "requestId": "d7b3..."
}
```

## 对话与图片消息

用户端对话页（`frontend/pages/voice/voice`）的文本与图片消息走同一组接口：**图片不单独开接口**，只换 `msgType` 与媒体引用字段。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/chat-sessions/current` | C 端登录 | 获取或创建当前会话（2.23） |
| `GET /api/v1/chat-sessions/{sessionId}/messages` | C 端登录 | 按游标读取当前用户的历史消息 |
| `POST /api/v1/chat-sessions/{sessionId}/messages` | C 端登录 | 写入用户消息（2.24）；`clientMsgId` 必填且幂等 |
| `POST /api/v1/chat-sessions/{sessionId}/completions` | C 端登录 | SSE 流式补全（2.25） |

`msgType` 语义：`1` 文本 / `2` 图片 / `3` 语音。

历史查询参数：`pageSize` 默认 20，范围 1–100；`beforeSeq` 可选正整数，表示仅取该序号之前的消息。首次不传游标，读取最新一页。返回 `data={records,hasMore,nextBeforeSeq}`，每页 `records` 按 `seqNo` 升序，向前翻页使用 `nextBeforeSeq`，以 `hasMore` 判断是否需要继续。仅返回当前用户拥有的会话中未删除的用户、助手消息，不返回系统提示词；会话不存在或不属于当前用户统一返回 404。记录字段包括 `id,seqNo,msgRole,msgType,contentText,mediaId,voiceMediaId,mediaUrl,modelCode,createdAt,finishReason`，Long ID 仍为字符串。`mediaUrl` 仅对仍可用且归属匹配的图片返回，媒体不可用时为 null，文本历史仍保留。重新进入当前会话时先恢复历史，再在末尾显示本次完整欢迎卡片；欢迎卡片仅用于展示，不写库、不触发模型调用。已结束或归档会话只读，历史可继续分页。

会话列表使用 `GET /api/v1/chat-sessions`，删除使用 `DELETE /api/v1/chat-sessions/{sessionId}`，置顶使用 `PATCH /api/v1/chat-sessions/{sessionId}/pinned`（`isPinned`），新对话复用 `/current` 并传 `forceNew=true`。这些接口与 V21 会话生命周期更新保持一致。

- `msgType=1`：必填 `contentText`；
- `msgType=2`：必填 `mediaId`；`contentText` 可同时携带用户随图片一起发的文字，也允许为空（纯图片）；
- `msgType=3`：必填 `voiceMediaId`（ASR 未接入，服务端暂不生成回复）。

**媒体引用必须属于当前用户。** 服务端对 `mediaId` / `voiceMediaId` 执行「主键 + `userId` + 业务类型 + 可用状态」的归属校验，业务类型分别为 `CHAT_IMAGE(4)`、`CHAT_VOICE(6)`；2.25 在读图之前会再校验一次。不属于当前用户的引用一律拒绝，且错误文案**不区分「不存在」与「不是你的」**，避免泄露他人 `mediaId` 是否存在。

**图片消息的上传顺序（三步不可颠倒）：**

1. `POST /api/v1/media-files/upload-credentials`（`bizType=4`）→ 得到 `storageKey` 与预签名 PUT URL；
2. 客户端直接把文件字节 PUT 到该 URL（有效期 300 秒，不经过后端）；
3. `POST /api/v1/media-files` 登记 → 得到 `mediaId`，此后才能用于 2.24。

开发环境默认使用后端自带的本地文件存储，不要求开发者另外安装 MinIO。此时第 1 步返回的是同一后端生成的一次性 PUT URL（`/api/v1/media-files/local-uploads/{token}`），该 URL 与本地文件读取地址仅在 `storage.provider=local` 时开放；向该本地上传地址 PUT 文件字节时必须携带 `Content-Type: application/octet-stream`，禁止使用 `application/json`，否则 JSON 请求过滤器会破坏二进制内容。`storage.provider=minio` 时开发态同样签发同源代理 PUT（`/api/v1/media-files/minio-uploads/{token}`），由后端写入 MinIO，避免浏览器依赖桶 CORS。部署默认 MinIO；若显式 `STORAGE_PROVIDER=aliyun-oss` 则使用私有 OSS 预签名 URL，并遵循凭证签名约束。除这一传输头差异外，调用方始终按上述三步协议处理。课程部署可显式关闭对象存储，按下方 disabled 契约拒绝上传。

**2.25 的 SSE 事件契约不变**：仍是 `delta` / `done` / `error`，`done` 必带完整正文。图片轮次对客户端**完全透明**——服务端内部先调用视觉模型产出客观描述，再把描述交给文本模型组织回答，2.25 的入参出参与文本轮次一模一样，前端不需要区分也不需要新增事件。

客户端注意两点：

- 图片轮次首字延迟明显高于文本轮次（要先完成一次视觉识别），沿用现有「正在思考…」占位即可，**不要用固定超时判定失败**；
- 服务端会把图片的描述写入该条消息，供后续轮次回看（例如发完图再问「那我晚饭吃多少」），因此多轮追问图片内容是支持的。


## 部署时关闭文本 AI

服务器设置 `SPRING_AI_MODEL_CHAT=none`、`AI_VISION_ENABLED=false` 时，不初始化模型客户端，不需要 DeepSeek Key。
非 AI 接口继续工作；实际请求文本 AI 时返回现有 ApiResponse 业务拒绝错误（BIZ_REJECTED），提示“文本 AI 未启用”。
接口路径、成功响应和 SSE 协议保持现有契约；启用时恢复 `SPRING_AI_MODEL_CHAT=deepseek` 并设置真实 Key。


## 课程部署：关闭对象存储

设置服务器环境变量 `STORAGE_PROVIDER=disabled` 时，不创建 OSS 或 MinIO 客户端，不需要云存储凭据。
媒体上传凭证、对象校验和读取等存储操作返回现有 ApiResponse 的 BIZ_REJECTED，提示“文件存储未启用，当前环境不支持文件上传与读取”。
不生成虚假上传 URL、不登记不存在的媒体、不返回模拟上传成功。
已有接口路径、DTO、响应封装保持不变；其他模块仍可使用数据库和 Redis。
此模式不覆盖上传功能验收，头像上传、饮食图片上传、图片聊天等依赖对象存储的操作不可用。
## 血糖仪 OCR 识别

血糖仪图片先按媒体三步流程上传并登记为 `GLUCOSE(2)`，随后创建 OCR 任务。OCR 只识别图片中的血糖数值和单位；餐前/餐后节点由用户进入拍照页前选择，服务端不得根据图片猜测 `nodeCode`。识别结果只用于预填，必须由用户确认或修改后再调用血糖记录接口入库。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/ocr-tasks` | C 端登录 | 创建异步识别任务；请求体 `{ "mediaId": 1001, "bizType": 1 }`，须携带稳定的 `X-Idempotency-Key` |
| `GET /api/v1/ocr-tasks/{id}` | C 端登录 | 查询当前用户的任务状态与识别结果 |

任务状态统一为：`0` 待处理、`1` 处理中、`2` 成功、`3` 失败。`bizType=1` 表示血糖识别；本期不开放 `bizType=2` 餐食 OCR。媒体必须属于当前用户、状态可用且业务类型为 `GLUCOSE(2)`。

成功结果中的 `ocrResult` 固定为：`schemaVersion=1`、`bizType=1`、`suggestedValue`（`mmol/L`，无法可靠识别时为 `null`）、`confidence`（`0-100`）和 `rawText`。即使状态成功，`suggestedValue=null` 也表示图片质量不足，客户端必须进入手工输入。任务失败时返回 `errorMsg`，客户端仍应允许用户手工记录。

用户确认后调用 `PUT /api/v1/glucose-records/by-node`：采纳 OCR 值时必须传 `sourceType=2` 和原 `mediaId`；用户改为手工输入时传 `sourceType=1`，但仍可携带原 `mediaId` 供追溯。非空 `mediaId` 必须属于当前用户、状态可用且类型为 `GLUCOSE(2)`。血糖值继续执行 `(0,40)` 的服务端范围校验，识别接口不得直接写入 `glucose_record`。

## 邮箱登录（合入）

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| POST /api/v1/auth/email/codes | 匿名 | 向邮箱发送 6 位验证码；scene 为 login 或 ind；同邮箱 60 秒冷却 |
| POST /api/v1/auth/email/sessions | 匿名 | 邮箱验证码登录 |
| PATCH /api/v1/users/me/email | C 端登录 | 绑定/换绑邮箱 |

## 设备数据同步

用户端通过 POST /api/v1/device/glucose-sync 提交厂商原始报文。请求体为 { provider, deviceSn?, payload }：provider 路由到对应适配器（MOCK / HUAWEI / HEALTH_CONNECT / APPLE_HEALTH / NIGHTSCOUT），适配器将 payload 转为统一血糖样本后按用户、业务日期和血糖节点幂等写入。MOCK 的 payload 为 { readings:[{ recordDate, nodeCode, value, unit, externalRecordId }] }。传入 deviceSn 时须已绑定当前用户且启用，成功后刷新 last_sync_at。

## 用户端设备管理

唯一认设备靠 deviceSn；选适配器用 provider 字符串（MOCK / HUAWEI / HEALTH_CONNECT / APPLE_HEALTH / NIGHTSCOUT）。会话内「待适配」草稿不写库，确认绑定后才进入下列接口。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| GET /api/v1/devices | C 端登录 | 当前用户已绑定设备列表（含禁用项，前端按 status 展示） |
| POST /api/v1/devices/bind | C 端登录 | 自助绑定：SN 空闲则新建并归属当前用户；已有空闲档案可认领；已被他人占用则冲突。建议写操作带 X-Idempotency-Key |
| POST /api/v1/devices/{id}/unbind | C 端登录 | 解绑：清空 user_id，保留档案；仅本人可操作 |

绑定请求体：`{ deviceSn, provider, deviceName? }`。列表项：`{ id, deviceSn, deviceName, provider, deviceStatus, lastSyncAt, bound }`（bound 恒为 true，表示归属当前用户）。

## 管理端设备档案

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| GET /admin/v1/devices | device:manage | 分页查询 |
| POST /admin/v1/devices | device:manage | 创建 |
| PUT /admin/v1/devices/{id} | device:manage | 更新 |
| PUT /admin/v1/devices/{id}/status | device:manage | 启用/禁用 |
| DELETE /admin/v1/devices/{id} | device:manage | 逻辑删除 |
