-- meal_record 增补预测元信息列，供 P5 统计按算法版本/推荐状态过滤。
-- 两列均可空：旧数据不回流，读侧遇 NULL 按「未标注」处理。
ALTER TABLE meal_record
    ADD COLUMN algorithm_version     VARCHAR(8)  NULL COMMENT '算法版本（v2.1 等）' AFTER suggest_text,
    ADD COLUMN recommendation_status VARCHAR(16) NULL COMMENT '推荐状态：IN_RANGE|ABOVE|BELOW|LOW|DANGER|INFEASIBLE' AFTER algorithm_version;
