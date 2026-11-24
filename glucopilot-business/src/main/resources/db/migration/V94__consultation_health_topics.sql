-- 求助分类改为字典；保留原编码及已有求助，不改历史正文。
CREATE TABLE consultation_topic (
    code VARCHAR(16) NOT NULL PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    group_name VARCHAR(16) NOT NULL,
    sort_no INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    CONSTRAINT ck_consultation_topic_enabled CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO consultation_topic (code,name,group_name,sort_no) VALUES
    ('GLUCOSE','血糖波动','血糖管理',10),
    ('FASTING','空腹血糖','血糖管理',20),
    ('POST_MEAL','餐后血糖','血糖管理',30),
    ('HYPOGLYCEMIA','低血糖','血糖管理',40),
    ('HYPERGLYCEMIA','高血糖','血糖管理',50),
    ('NIGHT_GLUCOSE','夜间血糖','血糖管理',60),
    ('DAWN','黎明现象','血糖管理',70),
    ('GLUCOSE_TREND','血糖趋势','血糖管理',80),
    ('GLUCOSE_TARGET','控糖目标','血糖管理',90),
    ('GLUCOSE_TEST','血糖监测','血糖管理',100),
    ('CGM','动态血糖','血糖管理',110),
    ('DEVICE','血糖仪使用','血糖管理',120),
    ('DIET','饮食咨询','饮食管理',130),
    ('FOOD_CHOICES','食物选择','饮食管理',140),
    ('CARBS','碳水搭配','饮食管理',150),
    ('GI','食物GI','饮食管理',160),
    ('MEAL_PORTIONS','餐食份量','饮食管理',170),
    ('EATING_OUT','外出就餐','饮食管理',180),
    ('MEDICATION','用药疑问','用药与复查',190),
    ('INSULIN','胰岛素使用','用药与复查',200),
    ('MEDICATION_TIME','用药时间','用药与复查',210),
    ('CHECKUP','复查解读','用药与复查',220),
    ('HBA1C','糖化血红蛋白','用药与复查',230),
    ('FOLLOW_UP','复诊准备','用药与复查',240),
    ('COMPLICATIONS','并发症筛查','用药与复查',250),
    ('EXERCISE','运动控糖','生活管理',260),
    ('WEIGHT','体重管理','生活管理',270),
    ('SLEEP','睡眠作息','生活管理',280),
    ('STRESS','情绪压力','生活管理',290),
    ('SICK_DAY','生病期间控糖','生活管理',300);

ALTER TABLE consultation_request DROP CHECK ck_consultation_topic;
ALTER TABLE consultation_request
    ADD CONSTRAINT fk_consultation_request_topic
    FOREIGN KEY (topic) REFERENCES consultation_topic(code);
