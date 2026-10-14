-- 分类图片可选；保持已有分类及食品关联不变。
ALTER TABLE food_category
    ADD COLUMN image_url VARCHAR(512) NULL COMMENT '分类配图URL' AFTER category_name;
