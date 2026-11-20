-- V81：窗口滑动观测（必留带 + 窗口滞回的上线配套）
--
-- 背景：装配器的历史窗口从「每轮固定 6 条」改成「上限 window-messages(20) 条 + 摘要指针之后的
-- 消息为必留带」。于是「这一轮到底发了多少条历史、有没有滑动」不再是配置决定的常量，
-- 而是每轮都不一样的量——不落表就只能靠日志 grep，管理端的「上下文构成」也解释不清
-- 「为什么这一轮命中率从 90% 掉到 20%」。
--
-- 两列都只写历史分片（其余分片的 context_key 不同，保持 NULL）。
-- window_messages 记的是「当时生效的上限」，用来区分「真发了 20 条」与「上限 20 但只装了 12 条」。

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_context' AND COLUMN_NAME = 'window_shifted'
);
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE chat_context ADD COLUMN window_shifted TINYINT(1) NULL COMMENT ''本轮窗口是否滑动（丢弃了窗口外的旧消息）；只有历史分片带值'' AFTER sort_no',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_context' AND COLUMN_NAME = 'window_messages'
);
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE chat_context ADD COLUMN window_messages INT UNSIGNED NULL COMMENT ''本轮生效的窗口条数上限（ai.memory.window-messages）；只有历史分片带值'' AFTER window_shifted',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
