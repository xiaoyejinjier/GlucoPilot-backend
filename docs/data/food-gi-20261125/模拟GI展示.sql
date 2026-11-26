-- 纯演示数字：没有GI测定或文献依据，不是营养事实。
-- 独立存放并仅通过giDemoValue展示，禁止用于健康判断或写入真实gi_value。
CREATE TABLE IF NOT EXISTS food_gi_demo_estimate (
    food_name VARCHAR(128) NOT NULL,
    category_code INT NOT NULL,
    gi_value DECIMAL(5,1) NOT NULL,
    note VARCHAR(256) NOT NULL DEFAULT '模拟数字，仅演示，不用于计算',
    enabled TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY(food_name, category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO food_gi_demo_estimate(food_name, category_code, gi_value) VALUES
    ('西兰花', 3, 15),
    ('黄瓜', 3, 15),
    ('西红柿', 3, 15),
    ('豆腐', 6, 15),
    ('无糖豆浆', 8, 30),
    ('杏仁', 7, 15),
    ('核桃', 7, 15),
    ('小笼包', 1, 60),
    ('紫皮黄心红薯', 1, 65),
    ('火腿肠', 2, 35),
    ('葱花', 3, 15);
