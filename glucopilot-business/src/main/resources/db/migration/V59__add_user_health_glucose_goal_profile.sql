-- 健康档案补充血糖控制目标人群：glucose_goal_profile（0一般/1较严格/2较宽松/3妊娠期，默认 0=一般）
-- 归属 profile.health 域（user_health 表）；校验区间在 DTO 层（@Min(0) @Max(3)，ProfileHealthConstant）。
-- 该字段独立于 diabetes_type，二者语义不耦合；diabetes_type 字段及其前端 picker 均不受影响。
ALTER TABLE user_health
    ADD COLUMN glucose_goal_profile TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '血糖控制目标人群 0一般 1较严格 2较宽松 3妊娠期' AFTER target_postprandial;