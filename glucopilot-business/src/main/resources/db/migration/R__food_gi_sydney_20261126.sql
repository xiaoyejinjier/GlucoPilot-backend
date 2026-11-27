-- 2026-11-26：按悉尼大学公开GI数据库核对的生西红柿参考值。
-- 前置：R__food_gi_reference_catalog.sql；独立导入时也须先执行该基础脚本。
-- 来源条目：Tomato (Solanum lycopersicum), raw #，GI 23，Jamaica，Normal，2h，n=10。
-- 原研究 Francis et al. 2019 报告23±11（均值±标准误），不是本批食品实测。
-- 仅补空值；不覆盖已有GI、其他营养字段、历史餐食快照或独立模拟展示数据。
START TRANSACTION;

-- 同义词只加入参考索引，不创建食品。后续新增同名/同分类食品可由现有任务补全。
INSERT IGNORE INTO food_gi_reference
 (food_name,category_code,gi_value,status,reference_food,source_url,note)
VALUES
 ('西红柿',3,23,'REFERENCE','Tomato (Solanum lycopersicum), raw #','https://glycemicindex.com/gi-search/','2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica'),
 ('番茄',3,23,'REFERENCE','Tomato (Solanum lycopersicum), raw #','https://glycemicindex.com/gi-search/','2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica'),
 ('生西红柿',3,23,'REFERENCE','Tomato (Solanum lycopersicum), raw #','https://glycemicindex.com/gi-search/','2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica'),
 ('生番茄',3,23,'REFERENCE','Tomato (Solanum lycopersicum), raw #','https://glycemicindex.com/gi-search/','2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica');

-- 仅替换上一版脚本中确切的“未找到”占位，不覆盖管理员维护的参考行。
UPDATE food_gi_reference
SET gi_value=23,status='REFERENCE',
 reference_food='Tomato (Solanum lycopersicum), raw #',
 note='2026-11-26核对悉尼GI数据库；Francis et al. 2019牙买加生西红柿，健康受试者10人、2小时，GI 23±11（均值±标准误，葡萄糖=100）。仅作生鲜形态的小样本研究参考，不适用于熟制、番茄汁或酱，不是当前食品实测。原研究：https://edelweisspublications.com/articles/33/397/Glycemic-Index-of-Selected-Foods-in-Jamaica'
WHERE food_name='西红柿' AND category_code=3 AND enabled=1
 AND gi_value IS NULL AND status='NEEDS_DETAILS'
 AND source_url='https://glycemicindex.com/gi-search/'
 AND note='未找到当前食物形态的可靠标准GI，不使用番茄汁或酱的GI。';

UPDATE food_nutrition f
JOIN food_gi_reference r
 ON r.food_name=f.food_name AND r.category_code=f.category_code
SET f.gi_value=r.gi_value,f.gmt_modified=f.gmt_modified
WHERE f.is_deleted=0 AND f.gi_value IS NULL
 AND r.enabled=1 AND r.status='REFERENCE' AND r.gi_value=23
 AND r.reference_food='Tomato (Solanum lycopersicum), raw #'
 AND r.source_url='https://glycemicindex.com/gi-search/'
 AND r.food_name IN ('西红柿','番茄','生西红柿','生番茄') AND r.category_code=3;
SET @food_gi_sydney_backfill_count=ROW_COUNT();

UPDATE food_catalog_cache_version
SET revision=revision+1,updated_at=CURRENT_TIMESTAMP
WHERE id=1 AND @food_gi_sydney_backfill_count>0;

INSERT IGNORE INTO food_gi_lookup
 (food_id,food_name,category_code,status,gi_value,reference_food,source_url,note,checked_at,next_attempt_at)
SELECT f.id,f.food_name,f.category_code,'REFERENCE',f.gi_value,
 r.reference_food,r.source_url,r.note,CURRENT_TIMESTAMP(3),TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(3))
FROM food_nutrition f
JOIN food_gi_reference r ON r.food_name=f.food_name AND r.category_code=f.category_code
WHERE f.is_deleted=0 AND f.gi_value=23 AND r.enabled=1 AND r.status='REFERENCE'
 AND r.gi_value=23 AND r.reference_food='Tomato (Solanum lycopersicum), raw #'
 AND r.source_url='https://glycemicindex.com/gi-search/'
 AND r.food_name IN ('西红柿','番茄','生西红柿','生番茄') AND r.category_code=3;

-- 使旧的缺失记录与本次参考值一致；清除旧租约，避免晚到的旧检索覆盖来源。
UPDATE food_gi_lookup q
JOIN food_nutrition f
 ON f.id=q.food_id AND f.food_name=q.food_name AND f.category_code=q.category_code
JOIN food_gi_reference r ON r.food_name=f.food_name AND r.category_code=f.category_code
SET q.status='REFERENCE',q.gi_value=23,q.reference_food=r.reference_food,
 q.source_url=r.source_url,q.note=r.note,q.checked_at=CURRENT_TIMESTAMP(3),
 q.next_attempt_at=TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(3)),q.lease_token=NULL
WHERE f.is_deleted=0 AND f.gi_value=23 AND r.enabled=1 AND r.status='REFERENCE'
 AND r.gi_value=23 AND r.reference_food='Tomato (Solanum lycopersicum), raw #'
 AND r.source_url='https://glycemicindex.com/gi-search/'
 AND r.food_name IN ('西红柿','番茄','生西红柿','生番茄') AND r.category_code=3
 AND q.gi_value IS NULL
 AND q.status IN ('PENDING','LOOKING_UP','NEEDS_DETAILS','NOT_FOUND','RETRY');
COMMIT;
