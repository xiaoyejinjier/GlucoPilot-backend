-- V73：chat_message 增加缓存用量两列
--
-- 背景：DeepSeek/OpenAI 的上下文缓存能把「命中部分」的输入 token 按约 1/10 计价，
-- 但我们的 chat_message 只存 prompt_tokens / completion_tokens，命中数从来没落过库，
-- 于是后台用量报表天然按全价估算——「省钱这件事到底有没有发生」无从验证。
--
-- 为什么不与 ai_call_log 合并成一个迁移：那两件事可以分两次上线，
-- 列先落地就能立刻在存量链路上积累命中数据；账本表（批次 1）设计还要再确认字段。
--
-- 为什么取不到时存 NULL 而不是 0：0 在报表里等价于「确实一次都没命中」，
-- 会把「我们没解析出来」伪装成「缓存没用上」，这比没有数据更糟。
-- 语义对齐 OpenAI 的 prompt_tokens_details.cached_tokens：
--   cache_hit_tokens  本次输入中命中缓存的部分
--   cache_miss_tokens 本次输入中未命中缓存的部分（= prompt_tokens - cache_hit_tokens）

SET @has_hit := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message'
                   AND COLUMN_NAME = 'cache_hit_tokens');
SET @ddl := IF(@has_hit = 0,
  'ALTER TABLE chat_message ADD COLUMN cache_hit_tokens INT UNSIGNED NULL COMMENT ''输入中命中上下文缓存的token数（取不到为NULL）'' AFTER completion_tokens',
  'DO 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_miss := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message'
                   AND COLUMN_NAME = 'cache_miss_tokens');
SET @ddl := IF(@has_miss = 0,
  'ALTER TABLE chat_message ADD COLUMN cache_miss_tokens INT UNSIGNED NULL COMMENT ''输入中未命中缓存的token数（取不到为NULL）'' AFTER cache_hit_tokens',
  'DO 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
