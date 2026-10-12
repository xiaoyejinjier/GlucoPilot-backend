-- 把会话/消息的并发唯一性从「应用层的先查后插」落到「数据库唯一约束」上。
--
-- 背景（三条都是实测复现过的竞态，经过与样例见 docs/AI对话-简单接入方案.md §23）：
--   ① 同一条用户消息被回答两次：selectAssistantReply 是普通快照读，而 chat_message 的唯一键
--      uk_session_client_active 只覆盖 client_msg_id 非空的行，助手消息的 client_msg_id 一直是 NULL
--      （实测 60/60 全为空）。两个并发 /completions 都通过幂等检查 → 两轮模型调用
--      （prompt_tokens 5855 + 5940）→ 落两条助手回复。
--   ② 同会话 seq_no 重复：MAX(seq_no)+1 后插入，而 idx_session_seq 非唯一。
--      实测两条助手消息都拿到 seq_no=2（同毫秒落库）。
--   ③ 每个用户可能有多条「进行中」会话：selectActiveByUserId → insert 之间没有任何互斥，
--      chat_session 上也没有对应唯一约束。
--
-- 修法的取舍：能用唯一约束表达的，就不引入锁或分布式协调。
--   本迁移只做两件事：先修复历史脏数据，再加约束；应用侧改为
--   「捕获 1062 → 回查已有行返回」，与用户消息路径（uk_session_client_active + 1062 当成功）保持同一套写法。
--
-- ⚠️ 两类写法上的注意事项（都会静默出错）：
--   1) 本迁移会 UPDATE chat_message.seq_no。该表的设计是「只追加」（此前 media_caption 是唯一的受控 UPDATE），
--      这是第二个受控例外：seq_no 只是「会话内展示用顺序号」，不承载业务语义，且不重排就无法建立唯一索引。
--      真正的排序兜底是自增主键 id（所有按序查询都是 ORDER BY seq_no DESC, id DESC），重排不影响查询语义。
--   2) MySQL 默认隔离级别是 REPEATABLE READ，普通 SELECT 读的是事务内第一次读的快照。
--      所以「把先查后插包进一个事务、查不到再查一次」是**无效修法**：并发方提交后，本事务再查仍然是空
--      （已实测）。唯一可靠的判据是唯一约束。


-- 1) 修复重复的 seq_no：只处理确实存在重复的会话，按 (seq_no, id) 保留原有相对顺序重排为 1..N。
--    用窗口函数 + 派生表 JOIN：MySQL 不允许 UPDATE 的目标表出现在子查询里，但物化的派生表可以
--    （窗口函数强制物化，因此这里读到的必然是更新前的快照）。
--    注意**不筛 is_deleted**：软删除的行同样占号（见 2) 的索引设计），必须一并重排。
UPDATE chat_message m
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY seq_no, id) AS rn
    FROM chat_message
) r ON r.id = m.id
JOIN (
    SELECT session_id
    FROM chat_message
    GROUP BY session_id, seq_no
    HAVING COUNT(*) > 1
) d ON d.session_id = m.session_id
SET m.seq_no = r.rn;


-- 2) 会话内顺序号唯一。
--    刻意**不含 is_deleted**：顺序号一旦分配就不复用，软删除的行也占着号。
--    配套改动：ChatMessageMapper.selectNextSeqNo 去掉了 is_deleted 过滤——
--    否则「删掉最大号那条 → MAX 回落 → 重算出的号撞上已删除的行」会让重试永远不成功。
ALTER TABLE chat_message
    ADD UNIQUE KEY uk_session_seq (session_id, seq_no);


-- 3) 每个用户至多一条「进行中」会话。
--    先归档多余的进行中会话，否则加索引会失败：保留 selectActiveByUserId 会选中的那一条
--    （最近有消息优先，其次 id 大者），其余置为「已结束」(2)。
UPDATE chat_session s
JOIN (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY user_id
        ORDER BY COALESCE(last_msg_at, gmt_create) DESC, id DESC
    ) AS rn
    FROM chat_session
    WHERE session_status = 1 AND is_deleted = 0
) r ON r.id = s.id
SET s.session_status = 2
WHERE r.rn > 1;

--    MySQL 没有条件唯一索引，用标准技巧：把条件投影成生成列。
--    唯一索引允许多个 NULL，于是「非进行中」的行（NULL）可以无限多，而「进行中」的行等于 user_id、至多一条。
--    表达式里包含 is_deleted 是必要的：软删除的进行中会话不该继续占名额。
--    STORED 而不是 VIRTUAL：唯一索引在 STORED 生成列上的行为最直白，且该表规模很小（会话量级），
--    建列时的表重建代价可以接受。
--    并发落败的一方捕获 1062 后回查即有会话，见 ChatSessionService.getOrCreate。
ALTER TABLE chat_session
    ADD COLUMN active_user_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (IF(session_status = 1 AND is_deleted = 0, user_id, NULL)) STORED
        COMMENT '仅进行中且未删除的会话等于 user_id，其余为 NULL；配合 uk_user_active 保证每用户至多一条进行中会话',
    ADD UNIQUE KEY uk_user_active (active_user_id);
