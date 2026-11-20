-- V82：修正 V81 两列的注释措辞（只改注释，不改数据）
--
-- V81 落地时这两列的注释写的是「窗口条数上限」，但装配器实际写入的是
-- **本轮真正回放进 prompt 的历史条数**（kept_history）。上限是配置项
-- `ai.memory.window-messages`（恒为 20），把它落表没有信息量；落实际条数才能回答
-- 「这一轮是真发了 20 条，还是上限 20 但只装了 3 条」——这正是排查命中率骤降要看的东西。
--
-- 列类型与数据都不动，只把注释改成与代码一致的说法。MODIFY COLUMN 本身幂等，
-- 重复执行只是重写一次注释。

ALTER TABLE chat_context
    MODIFY COLUMN window_shifted TINYINT(1) NULL
        COMMENT '本轮历史窗口是否滑动（丢弃了窗口外的旧消息）；只有历史分片带值';

ALTER TABLE chat_context
    MODIFY COLUMN window_messages INT UNSIGNED NULL
        COMMENT '本轮实际回放进 prompt 的历史条数（kept_history），不是上限；只有历史分片带值';
