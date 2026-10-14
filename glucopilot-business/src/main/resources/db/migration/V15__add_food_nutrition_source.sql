ALTER TABLE food_nutrition
    ADD COLUMN nutrition_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AI_ESTIMATE/AI_REVIEWED' AFTER shelf_status,
    ADD COLUMN source_model VARCHAR(128) NULL COMMENT '首次识别来源模型' AFTER nutrition_source;
