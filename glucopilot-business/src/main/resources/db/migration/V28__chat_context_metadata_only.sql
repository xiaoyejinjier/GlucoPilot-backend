-- ③ 期：让 chat_context 能只存"分片元数据"。
--
-- 版本号：原为 V26，合并远端（同号 V24/V25 被 auth_email_code 与 user_device 占用）后改为 V28。
--
-- 背景：V1 建 chat_context 时的设想是"把整包窗口 JSON 存进来"，因此有一条
--   CONSTRAINT chk_cc_payload CHECK (content_text IS NOT NULL OR content_json IS NOT NULL)
-- 大意是"一行要么有文本、要么有结构化内容"。
--
-- ③ 期的决定是**只写元数据**（哪几块分片、各占多少 token、属于哪一轮），
-- content_text / content_json **一律留 NULL**。理由有三条：
--   1. 隐私：原文在 chat_message、摘要在 chat_session.context_summary，
--      再把每轮 prompt 整包存一份，就是用户健康内容的第三份副本——用户行使删除权之后，
--      内容仍以"prompt 快照"的形式躺在另一张表里，删除权当场失效；
--   2. 体量：整包落库的增长是"对话量 × 窗口大小"，而这张表真正要回答的是
--      "这一轮为什么这么贵 / 为什么模型记不住前面"，那是**组成**问题，不是**内容**问题；
--   3. 定位内容有更好的路：trigger_msg_id / assistant_msg_id 已指向 chat_message 的对应行，
--      需要看原文按 ID 回查即可（还能顺带尊重那边的逻辑删除）。
--
-- 于是这条 CHECK 与"只记元数据"直接冲突：两列都为 NULL 时会被数据库拒绝。
-- 删的是**约束**，不是数据——表结构、索引、其它 CHECK（chk_cc_type / chk_cc_del*）全部保留。
--
-- 为什么写成"先查 information_schema 再 PREPARE"而不是直接 DROP CHECK：
--   MySQL 的 ALTER TABLE DROP CHECK 没有 IF EXISTS 语法，约束已经不存在时会直接报错。
--   而"约束可能已经不存在"是真实情况——本轮开发用的 dev 库在版本号重排前就执行过这条 DDL。
--   与 V22/V18 里补列用的是同一套写法，保证迁移在"全新库"与"已手工改过的库"上都能跑通。

SET @gp_drop_payload := (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE chat_context DROP CHECK chk_cc_payload',
        'DO 0')
      FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_context'
       AND CONSTRAINT_NAME = 'chk_cc_payload');
PREPARE gp_stmt FROM @gp_drop_payload; EXECUTE gp_stmt; DEALLOCATE PREPARE gp_stmt;
