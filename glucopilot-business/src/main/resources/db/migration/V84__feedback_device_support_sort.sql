-- 使用字典排序，编号不变；V83已经部署，不能修改原迁移。
UPDATE feedback_type
SET sort_no = 35
WHERE id = 5;
