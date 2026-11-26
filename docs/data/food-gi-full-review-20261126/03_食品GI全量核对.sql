-- 2026-11-26：对现有全部29项食品逐项检索，包括原有非空GI。
-- 12项可按既有常见形态对应网站研究，3项保留旧文献并标待核对，14项无适用/无匹配。
-- 网站不同研究不平均，保留小样本、配方和加工条件；不是当前批次实测。
-- 前置为GI参考索引基础脚本和当日西红柿补充脚本。先备份并选择正确业务库。
-- 使用名称+分类+原值作条件更新；保护不符合快照的人工修改，不修改历史餐食。
DROP TEMPORARY TABLE IF EXISTS food_gi_website_review_20261126;
CREATE TEMPORARY TABLE food_gi_website_review_20261126 (
 food_name VARCHAR(128) NOT NULL,category_code INT NOT NULL,prior_gi DECIMAL(5,1) NULL,
 website_gi DECIMAL(5,1) NULL,review_status VARCHAR(24) NOT NULL,
 reference_food VARCHAR(512) NULL,source_url VARCHAR(512) NULL,note VARCHAR(1800) NOT NULL,
 prior_ref_gi DECIMAL(5,1) NULL,prior_ref_source VARCHAR(512) NULL,
 prior_ref_note VARCHAR(1800) NULL,prior_lookup_note VARCHAR(1800) NULL,
 PRIMARY KEY(food_name,category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO food_gi_website_review_20261126 (food_name,category_code,prior_gi,website_gi,review_status,reference_food,source_url,note,prior_ref_gi,prior_ref_source,prior_ref_note,prior_lookup_note) VALUES
 ('米饭',1,83.2,83.0,'REFERENCE','White rice, NS','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；China、Normal、n=12、2h、测试年2005。按原有普通白米饭口径，网站将原研究83.2展示为83；糙米、糯米、低GI品种不同。参考研究：Yang, Y.X., et al., Glycemic index of cereals and tubers produced in China. World Journal of Gastroenterology, 2006. 12(21): p. 3430-3.不是当前批次食品实测。',83.2,'https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','普通熟米饭文献参考，不适用于炒饭、糯米或低GI米。','普通熟米饭文献参考，不适用于炒饭、糯米或低GI米。'),
 ('燕麦',1,55.0,55.0,'REFERENCE','Porridge oats','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；China、Normal、n=9、2h、测试年2005。沿用普通非即食燕麦粥参考；9名健康受试者，小样本人工核对，不适用于即食或生燕麦。参考研究：Yang, Y.X., et al., Glycemic index of cereals and tubers produced in China. World Journal of Gastroenterology, 2006. 12(21): p. 3430-3.不是当前批次食品实测。',55.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','名称未指定形态，按普通非即食燕麦粥参考55；不是该食品实测，不能用于即食燕麦。','名称未指定形态，按普通非即食燕麦粥参考55；不是该食品实测，不能用于即食燕麦。'),
 ('面条',1,46.0,46.0,'REFERENCE','Wheat noodle, dried','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；China、Normal、n=10、2h、测试年2005。沿用普通干小麦面条煮熟的参考形态；鲜面、方便面不同。参考研究：Yang, Y.X., et al., Glycemic index of cereals and tubers produced in China. World Journal of Gastroenterology, 2006. 12(21): p. 3430-3.不是当前批次食品实测。',46.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','名称未指定干鲜，按普通干小麦面条煮熟参考46；鲜面条、方便面不同。','名称未指定干鲜，按普通干小麦面条煮熟参考46；鲜面条、方便面不同。'),
 ('馒头',1,88.1,88.0,'REFERENCE','White wheat flour bread, steamed #','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；China、Normal、n=9、2h、测试年2005。精制小麦蒸馒头；网站将原研究88.1展示为88；9名健康受试者，小样本。参考研究：Yang, Y.X., et al., Glycemic index of cereals and tubers produced in China. World Journal of Gastroenterology, 2006. 12(21): p. 3430-3.不是当前批次食品实测。',88.1,'https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','精制小麦蒸馒头参考，不适用于全麦或加馅制品。','精制小麦蒸馒头参考，不适用于全麦或加馅制品。'),
 ('鸡胸肉',2,NULL,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库检索；没有适用的标准GI，不把其他混合餐当作本食物GI；缺乏标准测试所需可利用碳水，NULL不等于0。',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('鸡蛋',2,NULL,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库检索；没有适用的标准GI，不把其他混合餐当作本食物GI；缺乏标准测试所需可利用碳水，NULL不等于0。',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('瘦猪肉',2,NULL,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库检索；没有适用的标准GI，不把其他混合餐当作本食物GI；缺乏标准测试所需可利用碳水，NULL不等于0。',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('西兰花',3,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；未检出普通西兰花的标准GI条目。',NULL,'https://glycemicindex.com/gi-search/','低碳水蔬菜，未找到可可靠匹配的标准GI试验，不以15等经验数代替。','低碳水蔬菜，未找到可可靠匹配的标准GI试验，不以15等经验数代替。'),
 ('黄瓜',3,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；检出含腌黄瓜的混合米饭，不能当作黄瓜GI。',NULL,'https://glycemicindex.com/gi-search/','低碳水蔬菜，未找到可可靠匹配的标准GI试验，不以15等经验数代替。','低碳水蔬菜，未找到可可靠匹配的标准GI试验，不以15等经验数代替。'),
 ('西红柿',3,23.0,23.0,'REFERENCE','Tomato (Solanum lycopersicum), raw #','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；Jamaica、Normal、n=10、2h、测试年2019。仅生鲜西红柿；原研究23±11（均值±标准误），小样本；熟制、汁、酱不同。参考研究：Francis, R.D., et al., Glycemic index of selected foods in Jamaica. Pharmacovigil and Pharmacoepi, 2019. 2: p. 13-16.不是当前批次食品实测。',23.0,'https://glycemicindex.com/gi-search/','2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica','2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica'),
 ('苹果',4,36.0,44.0,'REFERENCE','Apples, raw','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；Australia、Normal、n=10、2h、测试年2020。普通生苹果参考，2020年澳大利亚研究；不适用于果汁、果干或罐头。由旧汇总均值36改为该网站条目44，属于更换参考研究。参考研究：Sydney University''s Glycemic Index Research Service (Sydney, Australia), unpublished observations.不是当前批次食品实测。',36.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','鲜苹果汇总参考，不适用于果汁或果干。','鲜苹果汇总参考，不适用于果汁或果干。'),
 ('香蕉',4,51.0,47.0,'REFERENCE','Banana, raw','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；Australia、Normal、n=10、2h、测试年2008。普通生香蕉参考；不适用于网站另列的过熟香蕉或特定品种。由旧汇总均值51改为该网站条目47，属于更换参考研究。参考研究：Sydney University''s Glycemic Index Research Service (Sydney, Australia), unpublished observations.不是当前批次食品实测。',51.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','鲜香蕉汇总参考，品种及成熟度会影响GI。','鲜香蕉汇总参考，品种及成熟度会影响GI。'),
 ('牛奶',5,39.0,30.0,'REFERENCE','Full-fat, milk','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；Australia、Normal、n=13、2h、测试年2018。沿用原来全脂牛奶的参考口径，采用2018年澳大利亚条目30；不适用于脱脂、调味奶或具体品牌配方。参考研究：Sydney University''s Glycemic Index Research Service (Sydney, Australia), unpublished observations.不是当前批次食品实测。',39.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','普通全脂牛奶汇总参考，不适用于调味奶。','普通全脂牛奶汇总参考，不适用于调味奶。'),
 ('酸奶',5,41.0,NULL,'NEEDS_DETAILS','Yogurt, fruit','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','2026-11-26全库复查https://glycemicindex.com/gi-search/；网站按原味/低脂/含糖/水果及品牌区分，41并非普通酸奶唯一对应值；保留原水果酸奶汇总41，待确认配方。原GI来自旧文献，不标记为网站已确认；原适用说明：名称未指定配方，使用水果酸奶汇总参考41；无糖原味及具体品牌需单独核对。',41.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','名称未指定配方，使用水果酸奶汇总参考41；无糖原味及具体品牌需单独核对。','名称未指定配方，使用水果酸奶汇总参考41；无糖原味及具体品牌需单独核对。'),
 ('豆腐',6,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；检出豆腐巧克力冷冻甜品及混合餐，没有普通豆腐对应条目。',NULL,'https://glycemicindex.com/gi-search/','普通豆腐与豆腐甜品不同，未找到可精确匹配的标准GI。','普通豆腐与豆腐甜品不同，未找到可精确匹配的标准GI。'),
 ('豆浆',6,34.0,NULL,'NEEDS_DETAILS','Soy milk','https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','2026-11-26全库复查https://glycemicindex.com/gi-search/；网站多为具体品牌和添加糖/麦芽糊精配方，未找到普通豆浆34的唯一对应；保留旧汇总34，待确认配方。原GI来自旧文献，不标记为网站已确认；原适用说明：普通豆浆汇总参考34；含糖量、浓度及品牌不同，不能视为当前配方实测。',34.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC2584181/','普通豆浆汇总参考34；含糖量、浓度及品牌不同，不能视为当前配方实测。','普通豆浆汇总参考34；含糖量、浓度及品牌不同，不能视为当前配方实测。'),
 ('杏仁',7,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；检出含杏仁糕点、巧克力及谷物棒，不能当作原味杏仁GI。',NULL,'https://glycemicindex.com/gi-search/','低可利用碳水坚果，无可靠匹配的标准GI，不将餐后混合餐试验当作杏仁GI。','低可利用碳水坚果，无可靠匹配的标准GI，不将餐后混合餐试验当作杏仁GI。'),
 ('核桃',7,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；没有原味核桃的对应条目。',NULL,'https://glycemicindex.com/gi-search/','低可利用碳水坚果，未找到可可靠匹配的标准GI。','低可利用碳水坚果，未找到可可靠匹配的标准GI。'),
 ('矿泉水',8,NULL,NULL,'NOT_APPLICABLE',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库检索；没有适用的标准GI，不把其他混合餐当作本食物GI；缺乏标准测试所需可利用碳水，NULL不等于0。',NULL,'https://glycemicindex.com/gi-search/','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。','缺乏标准GI测试所需的可利用碳水，不设置GI；空值不等于0。'),
 ('无糖豆浆',8,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；没有明确无糖且配方/浓度可对应的条目，不能套用品牌含糖豆饮。',NULL,'https://glycemicindex.com/gi-search/','无糖标签不能确定碳水浓度及工艺，不直接套用含糖或不同品牌豆饮料。','无糖标签不能确定碳水浓度及工艺，不直接套用含糖或不同品牌豆饮料。'),
 ('苏打饼干',9,74.0,NULL,'NEEDS_DETAILS','Soda crackers','https://lpi.oregonstate.edu/mic/food-beverages/glycemic-index-glycemic-load','2026-11-26全库复查https://glycemicindex.com/gi-search/；网站仅查到具体品牌Premium Soda Crackers 75、糖尿病受试组；不能替代无品牌苏打饼干，保留旧文献74。原GI来自旧文献，不标记为网站已确认；原适用说明：普通苏打饼干汇总参考74；具体品牌和配方可能不同。',74.0,'https://lpi.oregonstate.edu/mic/food-beverages/glycemic-index-glycemic-load','普通苏打饼干汇总参考74；具体品牌和配方可能不同。','普通苏打饼干汇总参考74；具体品牌和配方可能不同。'),
 ('榴莲',4,49.0,49.0,'REFERENCE','Durian (Durio zibethinus) #','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；Malaysia、Normal、n=8、2h、测试年2008。鲜榴莲参考；8名健康受试者，小样本；不适用于榴莲甜品。参考研究：Robert, S.D., et al., Glycemic index of common Malaysian fruits. Asia Pacific Journal of Clinical Nutrition, 2008. 17(1): p. 35-9.不是当前批次食品实测。',49.0,'https://apjcn.qdu.edu.cn/17_1_23.pdf','鲜榴莲小样本研究参考，不适用于甜品。','鲜榴莲小样本研究参考，不适用于甜品。'),
 ('葡萄',4,49.1,49.0,'REFERENCE','Grapes, NS #','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；Taiwan、Normal、n=20、2h、测试年2010。台湾健康受试者20人组，不使用同文糖尿病组；网站将原研究49.1展示为49，不适用于葡萄干或汁。参考研究：Chen, Y.Y., et al., Glycemia and peak incremental indices of six popular fruits in Taiwan: Healthy and type 2 diabetes subjects compared. Journal of Clinical Biochemistry and Nutrition, 2011. 49(3): p. 195-199.不是当前批次食品实测。',49.1,'https://pmc.ncbi.nlm.nih.gov/articles/PMC3208016/','鲜葡萄健康受试者研究参考，不适用于葡萄干或果汁。','鲜葡萄健康受试者研究参考，不适用于葡萄干或果汁。'),
 ('小笼包',1,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；检出其他肉包/饺类，未确认小笼包皮馅比例与做法。',NULL,'https://glycemicindex.com/gi-search/','皮馅比例及配方不明，不直接套用普通肉包或馒头GI。','皮馅比例及配方不明，不直接套用普通肉包或馒头GI。'),
 ('紫皮黄心红薯',1,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；网站区分品种及煮、烤、蒸，未找到本品种和做法，不用其他红薯代替。',NULL,'https://glycemicindex.com/gi-search/','品种与做法均影响GI，不能把普通煮红薯GI当作该品种实测。','品种与做法均影响GI，不能把普通煮红薯GI当作该品种实测。'),
 ('土豆块',3,66.4,66.0,'REFERENCE','Potato, NS, boiled','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；China、Normal、n=10、2h、测试年2005。沿用煮熟土豆口径；网站将原研究66.4展示为66；蒸、炸、泥或冷却后不同。参考研究：Yang, Y.X., et al., Glycemic index of cereals and tubers produced in China. World Journal of Gastroenterology, 2006. 12(21): p. 3430-3.不是当前批次食品实测。',66.4,'https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/','名称未指定烹调，按普通煮熟土豆参考66.4；蒸、炸、冷却或土豆泥不同。','名称未指定烹调，按普通煮熟土豆参考66.4；蒸、炸、冷却或土豆泥不同。'),
 ('炒饭',1,80.0,80.0,'REFERENCE','Fried Rice in Yangzhou-style, instant','https://glycemicindex.com/gi-search/','2026-11-26全库复查，采用悉尼GI数据库列值（葡萄糖=100）；China、Normal、n=15、2h、测试年2010。沿用原有扬州炒饭参考形态，配方不明时只作该形态参考，不能代表所有炒饭。参考研究：Chen, Y.-J., et al., Glycemic index and glycemic load of selected Chinese traditional foods. World Journal of Gastroenterology, 2010. 16(12): p. 1512-7.不是当前批次食品实测。',80.0,'https://pmc.ncbi.nlm.nih.gov/articles/PMC2846258/','名称未指定配方，使用文献扬州炒饭参考80；当前炒饭并非原试验食品，需按配方核对。','名称未指定配方，使用文献扬州炒饭参考80；当前炒饭并非原试验食品，需按配方核对。'),
 ('火腿肠',2,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；未找到本配方火腿肠条目；火腿混合餐和纯肉不能替代含淀粉香肠。',NULL,'https://glycemicindex.com/gi-search/','配方、淀粉含量及品牌不明，不能用纯肉类或其他加工肉GI代替。','配方、淀粉含量及品牌不明，不能用纯肉类或其他加工肉GI代替。'),
 ('葱花',3,NULL,NULL,'NOT_FOUND',NULL,'https://glycemicindex.com/gi-search/','2026-11-26全库复查https://glycemicindex.com/gi-search/；只检出含葱混合食物，没有单独葱花标准GI。',NULL,'https://glycemicindex.com/gi-search/','调味用量及可利用碳水低，无可可靠匹配的标准GI。','调味用量及可利用碳水低，无可可靠匹配的标准GI。');

START TRANSACTION;
UPDATE food_nutrition f
JOIN food_gi_website_review_20261126 v ON v.food_name=f.food_name AND v.category_code=f.category_code
SET f.gi_value=v.website_gi,f.gmt_modified=f.gmt_modified
WHERE f.is_deleted=0 AND v.review_status='REFERENCE'
 AND (f.gi_value IS NULL OR f.gi_value <=> v.prior_gi)
 AND NOT(f.gi_value <=> v.website_gi);
SET @food_gi_full_review_changes=ROW_COUNT();
UPDATE food_catalog_cache_version SET revision=revision+1,updated_at=CURRENT_TIMESTAMP
WHERE id=1 AND @food_gi_full_review_changes>0;

-- 只有仍为本机核对快照的参考行才更新，不覆盖管理员修改。
UPDATE food_gi_reference r
JOIN food_gi_website_review_20261126 v ON v.food_name=r.food_name AND v.category_code=r.category_code
SET r.gi_value=v.website_gi,r.status=v.review_status,r.reference_food=v.reference_food,
 r.source_url=v.source_url,r.note=v.note
WHERE r.enabled=1 AND r.gi_value <=> v.prior_ref_gi
 AND r.source_url <=> v.prior_ref_source AND r.note <=> v.prior_ref_note;
INSERT IGNORE INTO food_gi_reference
 (food_name,category_code,gi_value,status,reference_food,source_url,note)
SELECT food_name,category_code,website_gi,review_status,reference_food,source_url,note
FROM food_gi_website_review_20261126;

-- 未确认的旧值只保留于食品与检索记录，不再作为新增食品自动回填的参考数值。
UPDATE food_gi_lookup q
JOIN food_nutrition f ON f.id=q.food_id AND f.food_name=q.food_name AND f.category_code=q.category_code
JOIN food_gi_website_review_20261126 v ON v.food_name=f.food_name AND v.category_code=f.category_code
SET q.status=v.review_status,q.gi_value=f.gi_value,q.reference_food=v.reference_food,
 q.source_url=v.source_url,q.note=v.note,q.checked_at=CURRENT_TIMESTAMP(3),
 q.next_attempt_at=TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(3)),q.lease_token=NULL
WHERE f.is_deleted=0 AND q.note <=> v.prior_lookup_note
 AND f.gi_value <=> CASE WHEN v.review_status='REFERENCE' THEN v.website_gi ELSE v.prior_gi END;
INSERT IGNORE INTO food_gi_lookup
 (food_id,food_name,category_code,status,gi_value,reference_food,source_url,note,checked_at,next_attempt_at)
SELECT f.id,f.food_name,f.category_code,v.review_status,f.gi_value,v.reference_food,v.source_url,v.note,
 CURRENT_TIMESTAMP(3),TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(3))
FROM food_nutrition f
JOIN food_gi_website_review_20261126 v ON v.food_name=f.food_name AND v.category_code=f.category_code
WHERE f.is_deleted=0
 AND f.gi_value <=> CASE WHEN v.review_status='REFERENCE' THEN v.website_gi ELSE v.prior_gi END;
COMMIT;
DROP TEMPORARY TABLE food_gi_website_review_20261126;
