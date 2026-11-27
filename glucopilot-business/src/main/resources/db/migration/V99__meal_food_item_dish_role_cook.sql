-- V99：meal_food_item 增加「菜品名 / 主料配料 / 烹饪方式」三列。
--
-- 背景：识别粒度从「整盘菜一个食品」改为「食材级拆分」后，一条餐食会有 10~20 行明细。
-- 前端如果只是平铺一堆「青椒 / 猪里脊 / 米饭」，用户看不出哪几样属于同一道菜；
-- 也没有地方承载「这道菜是炒的还是蒸的」——而这两点都会影响用户对结果的信任。
--
-- 为什么存在 meal_food_item 而不是 meal_record 上的一张子表：明细本来就跟餐食同生共死，
-- 拆表只会让「覆盖写一次餐食」变成跨三张表的事务；这三列是同一条明细的属性，放这里最自然。
--
-- 为什么三列都可空：V99 之前的历史明细是「菜品级」，根本没有 dish_name 概念，
-- 也不该为了列的非空约束去猜一个菜品名（旧数据不迁移是这次改造的既定口径）。
-- 读侧把 NULL 当作「没有分组信息」，与「单独一种食物」是同一语义。
--
-- 复用既有幂等写法（information_schema + PREPARE）：迁移可以在任何已有该列的库上重复执行。
DROP PROCEDURE IF EXISTS add_column_if_missing_meal_food_item;

DELIMITER $$
CREATE PROCEDURE add_column_if_missing_meal_food_item()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'meal_food_item' AND COLUMN_NAME = 'dish_name'
    ) THEN
        ALTER TABLE meal_food_item
            ADD COLUMN dish_name VARCHAR(64) NULL COMMENT '所属菜品名（食材级拆分后的分组名）；NULL=无分组信息' AFTER food_name;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'meal_food_item' AND COLUMN_NAME = 'item_role'
    ) THEN
        ALTER TABLE meal_food_item
            ADD COLUMN item_role VARCHAR(16) NULL COMMENT 'MAIN=主料 / SEASONING=葱姜蒜等配料；NULL=未标注' AFTER dish_name;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'meal_food_item' AND COLUMN_NAME = 'cook_method'
    ) THEN
        ALTER TABLE meal_food_item
            ADD COLUMN cook_method VARCHAR(16) NULL COMMENT '烹饪方式：炒/炸/煎/烤/蒸/煮/炖/凉拌/生食/其他；NULL=未判断' AFTER item_role;
    END IF;
END$$
DELIMITER ;

CALL add_column_if_missing_meal_food_item();
DROP PROCEDURE IF EXISTS add_column_if_missing_meal_food_item;
