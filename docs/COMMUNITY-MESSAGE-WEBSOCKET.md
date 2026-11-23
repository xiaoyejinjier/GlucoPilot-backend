# 社区私信 WebSocket

私信现在使用原生 WebSocket 发送消息、接收变化通知。消息仍保存在 MySQL，历史和未读信息通过原 HTTP 接口读取。问诊交流保持原流程，本次不改动医生聊天协议。

## 代码入口

- `glucopilot-business/.../community/message/realtime/MessageWebSocketConfig.java`：注册 `/api/v1/community/messages/socket`，优先于 MVC 的 `/{peerUserId}` 路由；配置报文大小、空闲超时。
- `MessageSocketHandshake.java`：读取 Bearer，会话身份绑定到连接，令牌不进入 URL。
- `CommunityMessageSocket.java`：处理 PING、SEND，重新验证会话，调用原 `CommunityMessageService.send`。返回 SENT 才表示保存成功；并发写连接使用 Spring 的 `ConcurrentWebSocketSessionDecorator`。
- `CommunityMessageService.java`：保留双方账号行锁、发送规则、`sender_id + client_request_id` 唯一约束。只有新插入或实际标记已读才发布事件。
- `MessageRealtimeRelay.java`：`@TransactionalEventListener(AFTER_COMMIT)` 提交后通过 Redis Pub/Sub 转发小型事件。回滚不推送；事件只含双方 ID、消息 ID 和类型，没有正文。
- `CommunityMessageMapper.xml`：`selectMessagesAfter` 按 ID 升序补拉，只查询当前双方消息；最多50条一页。
- 小程序 `utils/message-realtime.js`：共享 SocketTask、25秒心跳、1～30秒退避重连、15秒确认超时、账号和服务器地址隔离。
- 小程序 `pages/messages/messages.js`：优先用 WebSocket 发送；尚未连接时走 HTTP。收到通知和重连成功后补拉，未确认的发送保留原请求编号供手动重试。
- 小程序 `utils/message-notifications.js` / `pages/information/information.js`：及时刷新全局角标和会话列表。打开具体聊天才标记已读。
- 管理端仓库 `nginx.conf`：HTTP/1.1、Upgrade/Connection 转发，90秒读取超时覆盖心跳。

## 消息流程

1. 小程序使用当前 API 地址换成 WS/WSS，携带当前登录 Bearer 建连接，服务端返回 READY。
2. 发送 SEND：`{type, peerUserId, content, clientRequestId}`。服务端使用连接绑定的发送者身份，不接受客户端冒充发送人。
3. Service 在事务内加锁、查幂等编号、检查陌生人首条限制、保存消息。相同编号重试返回原记录。
4. 提交后 Redis 转发 DM_CHANGED 给双方在线连接；发送端收到携带保存结果的 SENT 确认。事件与确认顺序不作保证，按消息 ID 合并去重。
5. 接收端补拉数据库记录，渲染后提交 throughId 已读边界；已读变化同样提交后通知。关闭页面不自动已读。
6. 离线消息保留数据库，重连按 afterId 连续补拉；消息通知不是持久队列。Redis短时异常不会撤销已提交消息，客户端仍有 HTTP 定时核对。

## 本地和部署验证

1. 在 IDEA 刷新 Maven、重建并重启后端，重新编译小程序；不需要数据库迁移。
2. 用两个不同账号进入同一会话，确认新消息及时出现、退出聊天后角标提醒；互关或回复后的发送限制仍由服务端核验。
3. 断网后再恢复，观察连接重建、补拉、请求编号重试；切换账号不显示上个账号的消息。前台正常聊天30秒兜底检查，连接失败和等待回复时5秒检查；全局其他提醒仍沿用汇总轮询。
4. 部署时加载管理端仓库的新 Nginx 配置。正式小程序为对应 HTTPS 服务配置 WSS socket 合法域名与有效证书；本地开发使用现有 `utils/config.js` 地址设置，真机不能使用电脑的 localhost。
5. 多个后端实例必须共用同一业务数据库和 Redis。Pub/Sub 不提供离线保留，仍以数据库游标补拉为准。

## 已验证范围

- 隔离 Tomcat + JDK WebSocket 客户端完成真实 HTTP Upgrade、READY/PING/PONG/SEND/SENT；匿名握手401；没有使用真实账号或连接共享业务库。
- 自动测试覆盖身份冒充、会话失效和被替换、第三人隔离、幂等不重复通知、事务提交/回滚、Redis失败降级、超过一页的离线补拉、切换账号和旧回调丢弃。
- 用户本地联调已确认101握手、正常收发、已读同步、全局通知及断网重连后收到新消息；服务器WSS部署仍需按上述步骤验证。

实现参考：[Spring 原生 WebSocket 服务端与并发发送说明](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)。
