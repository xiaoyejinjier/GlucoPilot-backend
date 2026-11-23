-- 阶段 2b-3b：识别快照令牌 + 识别时营养库匹配状态。
-- 两列均可空、均不加索引；3b1 上线期间一律保持 NULL，自 3b2 起才开始写入。
-- library_status 由 3b2 起在 replaceItems 时写入（来源：快照命中用快照值，否则用保存时归一化值），
-- 供 2b-3c 区分「未确认的识别项」；V89 已错过该列，V90 是最后一个低成本窗口。

ALTER TABLE meal_record
    ADD COLUMN analysis_token VARCHAR(64) NULL
        COMMENT '保存时引用的识别快照令牌；可空=旧客户端或未经识别'
        AFTER analysis_status;

ALTER TABLE meal_food_item
    ADD COLUMN library_status VARCHAR(16) NULL
        COMMENT '识别时的营养库匹配状态：AVAILABLE|PENDING_REVIEW|UNRESOLVED；可空=历史/未识别'
        AFTER nutrition_source;
