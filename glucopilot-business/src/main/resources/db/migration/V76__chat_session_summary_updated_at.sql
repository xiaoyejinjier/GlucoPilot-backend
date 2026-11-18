-- V76：摘要任务的"最小间隔"门需要一个准确的时间戳
--
-- 背景（批次 3 / T3.2）：`maybeSummarizeAsync` 原先每轮都会试着摘要一次——每轮新增 2 条消息、
-- 保底只留 6 条，从第 4 轮起每轮都有消息溢出窗口，于是每轮都调一次摘要模型。
-- 除了摘要调用本身的成本，它还会把摘要块反复重写，连带废掉后面整段历史的前缀缓存
-- （实测：摘要重写那一轮缓存命中 24%，下一轮回到 93%）。
--
-- 门控需要三样东西：候选条数、候选字数、距上次摘要多久。前两个从消息表算，
-- 第三个必须有"上次成功写摘要的时间"——刻意不复用 `gmt_modified`：
-- 这张表还会因为改名、结束会话、状态流转而更新，用它会让间隔门被一次改名重置。
--
-- 幂等：列存在就跳过，重复执行无副作用。

SET @has_column := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'chat_session'
       AND COLUMN_NAME = 'summary_updated_at'
);
SET @ddl := IF(@has_column = 0,
    'ALTER TABLE chat_session ADD COLUMN summary_updated_at DATETIME(3) NULL COMMENT ''上次成功写滚动摘要的时间（摘要任务的间隔门与运维判读用）'' AFTER summary_upto_msg_id',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
