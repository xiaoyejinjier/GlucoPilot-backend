-- 会话生命周期闭环（5.3 列表 / 5.4 删除 / 历史分页 / 新建 / 置顶）+ 两个结构决定（B4 / B3）。
--
-- 背景：会话链路的"管道"是扎实的（单飞、幂等、取消不落库、失败轮次部分内容落库），
-- 但**会话生命周期**完全没做：列表/删除接口没实现、会话内历史读不出来、没有归档也没有"新建会话"，
-- 一个用户永远只有一条进行中的会话。本迁移只做两件与结构有关的事：
--
--   1) B4 —— chat_session.window_msg_count 标注废弃。
--      这一列在创建会话时写一次（值取自 ai.memory.window-messages），但**读取侧从不读它**
--      （ChatMemoryReadAdvisor 用的是全局配置），属于"看起来能按会话调窗口、实际不生效"的误导性字段。
--      处置决定（2026-10-15）：**保留并标注废弃**，不 DROP——因为记忆的下一阶段要做 token 预算，
--      那时很可能引入新的"记忆预算"列，现在删了又要加。列本身与 chk_cs_window 约束都不动，只改注释。
--
--   2) B3 —— 新增 chat_tool_call：工具调用的**元数据**（工具名/成功与否/耗时/第几次）。
--      现状：chat_message 里没有任何 tool 列，工具调用与返回发生在 ToolCallingAdvisor 内部、不落库，
--      于是"这条回答当时查了哪些数据"完全不可追溯，追问同一份数据只能重新调工具（重复计费）。
--      处置决定（2026-10-15）：**本期只落元数据，不落工具返回内容**——工具返回里是用户血糖、
--      饮食记录等健康数据，落库等于新增一份健康数据副本，权限与脱敏要单独设计，且真正的用途
--      （把工具结果回放进上下文）属于记忆阶段。完整 trace 将来在本表上加列即可，不必改结构。
--
-- 表名沿用 chat_* 前缀（chat_session / chat_message / chat_context / chat_user_memory 同一族）。
-- 只追加、不改写：没有 is_deleted / 审计列，删会话时本表数据保留（它是"当时发生了什么"的事实）。

-- ---------------------------------------------------------------------------
-- 1) B4：标注废弃（类型与默认值保持不变，chk_cs_window 约束继续有效）
-- ---------------------------------------------------------------------------
ALTER TABLE chat_session
    MODIFY COLUMN window_msg_count SMALLINT UNSIGNED NOT NULL DEFAULT 20
        COMMENT '已废弃(V21)：窗口统一由 ai.memory.window-messages 决定，读取侧不再读本列';

-- ---------------------------------------------------------------------------
-- 2) B3：工具调用元数据
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_tool_call (
  id           BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '主键',
  session_id   BIGINT UNSIGNED   NOT NULL COMMENT '会话ID（chat_session.id）',
  user_id      BIGINT UNSIGNED   NOT NULL COMMENT '用户ID',
  user_msg_id  BIGINT UNSIGNED   NULL COMMENT '本轮用户消息ID（同一轮的多次工具调用共享它，可用于关联助手回复）',
  round_key    VARCHAR(64)       NULL COMMENT '本轮单飞键 chat-round:<userMsgId>，排障时与日志对齐',
  tool_name    VARCHAR(64)       NOT NULL COMMENT '工具名（与 @Tool(name=...) 一致）',
  call_index   SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同一轮内的第几次调用，从1开始',
  ok           TINYINT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '是否成功 0失败1成功',
  duration_ms  INT UNSIGNED      NULL COMMENT '执行耗时毫秒',
  error_type   VARCHAR(128)      NULL COMMENT '失败时的异常类名（只记类名，不记消息正文，避免把用户数据写进库）',
  gmt_create   DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
  CONSTRAINT pk_ctc_id PRIMARY KEY (id),
  KEY idx_ctc_session (session_id, gmt_create),
  KEY idx_ctc_user_msg (user_msg_id),
  CONSTRAINT chk_ctc_ok CHECK (ok IN (0, 1)),
  CONSTRAINT chk_ctc_index CHECK (call_index >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话内工具调用元数据（B3：只记谁被调了/成功与否/耗时，不记返回内容）';
