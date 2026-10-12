-- 健康档案补充身体指标：身高 / 体重（均可空，NULL = 未填写，与既有字段语义一致）
-- 归属 profile.health 域（user_health 表）；校验区间仅在 DTO 层（沿用 target_* 策略）
ALTER TABLE user_health
    ADD COLUMN height_cm INT UNSIGNED NULL COMMENT '身高cm 50~250' AFTER exercise_habit,
    ADD COLUMN weight_kg DECIMAL(4,1) NULL COMMENT '体重kg 20.0~300.0' AFTER height_cm;
