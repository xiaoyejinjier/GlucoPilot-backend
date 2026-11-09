-- GI使用葡萄糖=100标尺；只补充已有食品的缺失值，不覆盖人工维护的数据。
-- 逐项来源、适用食物形态和未补充原因见 docs/FOOD-GI-DATA.md。
-- 米饭、馒头：Yang et al. 2006，表2，普通熟米饭/精制小麦蒸馒头。
-- https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/
-- 苹果、香蕉、牛奶：Atkinson et al. 2008，表1，鲜果/普通全脂牛奶参考值。
-- https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/
-- 榴莲：Robert et al. 2008，表2，马来西亚鲜榴莲参考值，小样本研究。
-- https://apjcn.qdu.edu.cn/17_1_23.pdf
-- 葡萄：Chen et al. 2011，健康受试者鲜葡萄均值。
-- https://pmc.ncbi.nlm.nih.gov/articles/PMC3208016/

UPDATE food_nutrition
SET gi_value = CASE food_name
    WHEN '米饭' THEN 83.2
    WHEN '馒头' THEN 88.1
    WHEN '苹果' THEN 36.0
    WHEN '香蕉' THEN 51.0
    WHEN '牛奶' THEN 39.0
    WHEN '榴莲' THEN 49.0
    WHEN '葡萄' THEN 49.1
    ELSE gi_value
END
WHERE is_deleted = 0
  AND gi_value IS NULL
  AND food_name IN ('米饭', '馒头', '苹果', '香蕉', '牛奶', '榴莲', '葡萄');

SET @food_gi_updated_rows = ROW_COUNT();

-- 与数据更新共用事务；新版本键使已缓存的空GI详情立即失效。
UPDATE food_catalog_cache_version
SET revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1
  AND @food_gi_updated_rows > 0;
