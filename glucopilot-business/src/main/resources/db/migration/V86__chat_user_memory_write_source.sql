-- V86：长期记忆的「写入来源」列
--
-- 背景：同一个 (user_id, memory_key, item_hash) 可能被两条完全不同的路径写进来——
--   1. 用户当轮亲口说「我不吃香菜了」→ 模型调 save_long_term_memory（WriteSource.EXPLICIT）；
--   2. 会话结束/归档补齐时模型复盘整段会话抽出来的（WriteSource.BATCH）。
-- 两者的可信度差得很远：第一条是用户的原话，第二条是模型对闲聊的推断。
-- 现有表只看得到 confidence（0~100 的分数），看不到「这话是谁说的」，
-- 运营在审计页面对一条可疑记忆无法回答最关键的问题：这是用户说的，还是模型猜的？
--
-- 于是加一列存枚举名。为什么是字符串而不是 0/1：
-- 将来若出现第三种来源（例如「管理端代录」），加值不需要改表；
-- 而且导出审计时不用再查一张映射表。
--
-- 值域与语义：
--   EXPLICIT = 用户当轮亲口要求记住（模型调用 save_long_term_memory）
--   BATCH    = 会话结束 / 归档补齐时的批量复盘（模型抽取）
--   NULL     = 本列上线之前写入的历史数据（无法追溯，UI 显示「—」而不是硬猜一个）
--
-- 只加列，不回填：历史行到底来自哪条路径，只能靠 gmt_create 与会话时间比对去猜，猜错比留空更糟。

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_user_memory' AND COLUMN_NAME = 'write_source'
);
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE chat_user_memory ADD COLUMN write_source VARCHAR(16) NULL COMMENT ''写入来源：EXPLICIT=用户当轮亲口 / BATCH=会话结束后模型抽取 / NULL=本列上线前的历史数据'' AFTER source_session_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
