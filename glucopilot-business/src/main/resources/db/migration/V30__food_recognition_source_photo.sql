ALTER TABLE food_nutrition
    ADD COLUMN source_media_id BIGINT NULL COMMENT '餐食识别来源照片，仅用于运营审核预览' AFTER source_model;
