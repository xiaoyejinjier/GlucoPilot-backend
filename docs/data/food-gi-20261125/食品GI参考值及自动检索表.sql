SET NAMES utf8mb4;
-- 食品GI文献参考与后续自动检索。先备份；选择目标业务数据库后执行。
-- 本脚本可重复执行；不覆盖已有GI，不改食品热量、碳水、图片、归属或历史餐食快照。
-- GI使用葡萄糖=100标尺；REFERENCE为参考值，不能描述为本食品/患者实测。
-- 使用repeatable迁移，避免与远端V98设备迁移撞号。逐项来源与适用条件同时入库。
CREATE TABLE IF NOT EXISTS food_gi_reference (
 food_name VARCHAR(128) NOT NULL,
 category_code INT NOT NULL,
 gi_value DECIMAL(5,1) NULL,
 status VARCHAR(24) NOT NULL,
 reference_food VARCHAR(512) NULL,
 source_url VARCHAR(512) NULL,
 note VARCHAR(1800) NULL,
 enabled TINYINT NOT NULL DEFAULT 1,
 PRIMARY KEY(food_name,category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS food_gi_lookup (
 food_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 food_name VARCHAR(128) NOT NULL,
 category_code INT NULL,
 status VARCHAR(24) NOT NULL,
 gi_value DECIMAL(5,1) NULL,
 reference_food VARCHAR(512) NULL,
 source_url VARCHAR(512) NULL,
 note VARCHAR(1800) NULL,
 checked_at DATETIME(3) NULL,
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 lease_token VARCHAR(36) NULL,
 KEY idx_food_gi_pending(next_attempt_at,food_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;
-- INSERT IGNORE保留部署后由管理员维护的参考索引，不强行覆盖。
INSERT IGNORE INTO food_gi_reference
 (food_name,category_code,gi_value,status,reference_food,source_url,note)
VALUES
 ('米饭',1,83.2,'REFERENCE','Cooked rice','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','普通熟米饭文献参考，不适用于炒饭、糯米或低GI米。'),
 ('馒头',1,88.1,'REFERENCE','Steamed bread (refined)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','精制小麦蒸馒头参考，不适用于全麦或加馅制品。'),
 ('苹果',4,36,'REFERENCE','Apple, raw','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','鲜苹果汇总参考，不适用于果汁或果干。'),
 ('香蕉',4,51,'REFERENCE','Banana, raw','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','鲜香蕉汇总参考，品种及成熟度会影响GI。'),
 ('牛奶',5,39,'REFERENCE','Milk, full fat','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','普通全脂牛奶汇总参考，不适用于调味奶。'),
 ('榴莲',4,49,'REFERENCE','Durian, fresh','https://apjcn.qdu.edu.cn/17_1_23.pdf','鲜榴莲小样本研究参考，不适用于甜品。'),
 ('葡萄',4,49.1,'REFERENCE','Grapes, fresh','https://pmc.ncbi.nlm.nih.gov/articles/PMC3208016/','鲜葡萄健康受试者研究参考，不适用于葡萄干或果汁。'),
 ('燕麦',1,55,'REFERENCE','Oatmeal (unpackaged)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','名称未指定形态，按普通非即食燕麦粥参考55；不是该食品实测，不能用于即食燕麦。'),
 ('面条',1,46,'REFERENCE','Wheat Noodle (dried)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','名称未指定干鲜，按普通干小麦面条煮熟参考46；鲜面条、方便面不同。'),
 ('酸奶',5,41,'REFERENCE','Yogurt, fruit','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','名称未指定配方，使用水果酸奶汇总参考41；无糖原味及具体品牌需单独核对。'),
 ('豆浆',6,34,'REFERENCE','Soy milk','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','普通豆浆汇总参考34；含糖量、浓度及品牌不同，不能视为当前配方实测。'),
 ('苏打饼干',9,74,'REFERENCE','Soda crackers','https://lpi.oregonstate.edu/mic/food-beverages/glycemic-index-glycemic-load','普通苏打饼干汇总参考74；具体品牌和配方可能不同。'),
 ('土豆块',3,66.4,'REFERENCE','Potato (cooked)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','名称未指定烹调，按普通煮熟土豆参考66.4；蒸、炸、冷却或土豆泥不同。'),
 ('炒饭',1,80,'REFERENCE','Fried Rice in Yangzhou-style, instant','https://pmc.ncbi.nlm.nih.gov/articles/PMC2846258/','名称未指定配方，使用文献扬州炒饭参考80；当前炒饭并非原试验食品，需按配方核对。'),
 ('鸡胸肉',2,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('鸡蛋',2,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('瘦猪肉',2,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('矿泉水',8,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('西兰花',3,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','低碳水蔬菜，未找到可可靠匹配的标准GI试验，不以15等经验数代替。'),
 ('黄瓜',3,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','低碳水蔬菜，未找到可可靠匹配的标准GI试验，不以15等经验数代替。'),
 ('西红柿',3,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','未找到当前食物形态的可靠标准GI，不使用番茄汁或酱的GI。'),
 ('豆腐',6,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','普通豆腐与豆腐甜品不同，未找到可精确匹配的标准GI。'),
 ('无糖豆浆',8,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','无糖标签不能确定碳水浓度及工艺，不直接套用含糖或不同品牌豆饮料。'),
 ('杏仁',7,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','低可利用碳水坚果，无可靠匹配的标准GI，不将餐后混合餐试验当作杏仁GI。'),
 ('核桃',7,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','低可利用碳水坚果，未找到可可靠匹配的标准GI。'),
 ('小笼包',1,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','皮馅比例及配方不明，不直接套用普通肉包或馒头GI。'),
 ('紫皮黄心红薯',1,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','品种与做法均影响GI，不能把普通煮红薯GI当作该品种实测。'),
 ('火腿肠',2,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','配方、淀粉含量及品牌不明，不能用纯肉类或其他加工肉GI代替。'),
 ('葱花',3,NULL,'NEEDS_DETAILS',NULL,'https://glycemicindex.com/gi-search/','调味用量及可利用碳水低，无可可靠匹配的标准GI。'),
 ('煮米饭',1,83.2,'REFERENCE','Cooked rice','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('白馒头',1,88.1,'REFERENCE','Steamed bread (refined)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('干小麦面条',1,46,'REFERENCE','Wheat Noodle (dried)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('挂面',1,46,'REFERENCE','Wheat Noodle (dried)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('鲜小麦面条',1,81.6,'REFERENCE','Wheat Noodle (fresh)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('非即食燕麦粥',1,55,'REFERENCE','Oatmeal (unpackaged)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('荞麦面条',1,59.3,'REFERENCE','Noodle (buckwheat)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('小米粥',1,61.5,'REFERENCE','Millet porridge','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('大米粥',1,69.4,'REFERENCE','Rice porridge','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('黑米粥',1,42.3,'REFERENCE','Black rice porridge','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('全麦面包',1,69,'REFERENCE','Bread (whole wheat)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('糯米饭',1,87,'REFERENCE','Sticky rice (cooked)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('煮甜玉米',1,55,'REFERENCE','Sweet corn (cooked)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('水煮土豆',3,66.4,'REFERENCE','Potato (cooked)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('蒸土豆',3,62,'REFERENCE','Potato (steam)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('土豆泥',3,73,'REFERENCE','Potato mashed','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('蒸山药',3,51,'REFERENCE','Yam (steam)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('煮山药',3,54,'REFERENCE','Yam (cooked)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。'),
 ('煮芋头',3,47.7,'REFERENCE','Taro (cooked)','https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','仅对应名称指明的原试验加工形态，作为文献参考，不代表当前批次实测。');

UPDATE food_nutrition f
JOIN food_gi_reference r ON r.food_name=f.food_name AND r.category_code=f.category_code AND r.enabled=1
SET f.gi_value=r.gi_value
WHERE f.is_deleted=0 AND f.gi_value IS NULL AND r.status='REFERENCE' AND r.gi_value IS NOT NULL;
SET @food_gi_backfill_count=ROW_COUNT();
UPDATE food_catalog_cache_version SET revision=revision+1,updated_at=CURRENT_TIMESTAMP
WHERE id=1 AND @food_gi_backfill_count>0;

INSERT IGNORE INTO food_gi_lookup
 (food_id,food_name,category_code,status,gi_value,reference_food,source_url,note,checked_at,next_attempt_at)
SELECT f.id,f.food_name,f.category_code,
 CASE WHEN f.gi_value IS NOT NULL AND (r.gi_value IS NULL OR f.gi_value!=r.gi_value)
      THEN 'EXISTING' ELSE r.status END,
 f.gi_value,r.reference_food,r.source_url,r.note,CURRENT_TIMESTAMP(3),
 TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(3))
FROM food_nutrition f
JOIN food_gi_reference r ON r.food_name=f.food_name AND r.category_code=f.category_code AND r.enabled=1
WHERE f.is_deleted=0;
COMMIT;
