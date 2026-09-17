-- Flyway V2: baseline seed data

INSERT INTO dict_glucose_node (node_code, node_name, short_name, is_meal, sort_no) VALUES
('before_breakfast', '早餐前', '早前', 0, 1),
('breakfast', '早餐', '早点', 1, 2),
('after_breakfast', '早餐后', '早后', 0, 3),
('before_lunch', '中餐前', '中前', 0, 4),
('lunch', '中餐', '中点', 1, 5),
('after_lunch', '中餐后', '中后', 0, 6),
('before_dinner', '晚餐前', '晚前', 0, 7),
('dinner', '晚餐', '晚点', 1, 8),
('after_dinner', '晚餐后', '晚后', 0, 9);

INSERT INTO sys_role (role_code, role_name, sort_no, remark) VALUES
('super_admin', '超级管理员', 1, '全部权限：含用户管理、健康档案查看与系统管理'),
('operator', '运营', 2, '业务运营：资讯/轮播/媒体/营养库/反馈/导出/推送；无用户与系统级配置'),
('editor', '内容编辑', 3, '内容向：资讯/轮播/媒体/营养库');

INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id, path_url, sort_no) VALUES
('dashboard:view', '数据看板', 1, 0, '/dashboard', 1),
('content:news:list', '资讯管理', 1, 0, '/content/news', 2),
('content:news:edit', '资讯编辑发布', 2, 0, NULL, 3),
('content:banner:manage', '轮播图管理', 1, 0, '/content/banners', 4),
('content:media:manage', '媒体素材管理', 1, 0, '/content/media', 5),
('food:library:manage', '营养库管理', 1, 0, '/nutrition/foods', 6),
('feedback:manage', '反馈管理', 1, 0, '/feedback', 7),
('feedback:reply', '官方回复', 2, 0, NULL, 8),
('export:task:manage', '导出任务管理', 1, 0, '/exports', 9),
('user:manage', '用户管理', 1, 0, '/users', 10),
('health:archive:view', '健康档案查看', 1, 0, '/user-service/archives', 11),
('message:push:manage', '消息推送', 1, 0, '/message/push', 12),
('ai:config:manage', 'AI服务配置', 1, 0, '/ai/config', 13),
('system:role:manage', '角色权限管理', 1, 0, '/system/roles', 14),
('system:config:manage', '系统参数配置', 1, 0, '/system/config', 15),
('system:log:view', '操作日志查看', 1, 0, '/system/logs', 16);

-- 【开发种子】生产环境请删除下方 ops 账号相关 INSERT，改走管理员创建流程。
-- 此处账号默认禁用，password_hash 为不可用占位，禁止直接上线使用。
INSERT INTO ops_account (id, username, password_hash, account_status) VALUES
(1, 'admin', '!invalid-hash-replace-via-bootstrap', 0);

INSERT INTO ops_profile (ops_id, real_name) VALUES (1, '系统管理员');

INSERT INTO sys_ops_role (ops_id, role_id) VALUES (1, 1);

-- 超管：全部权限
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 1, id FROM sys_permission WHERE is_deleted = 0;

-- 运营：显式 IN 列表（禁止 NOT IN）
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 2, id FROM sys_permission
WHERE is_deleted = 0
  AND perm_code IN (
    'dashboard:view',
    'content:news:list', 'content:news:edit',
    'content:banner:manage', 'content:media:manage',
    'food:library:manage',
    'feedback:manage', 'feedback:reply',
    'export:task:manage',
    'message:push:manage',
    'system:log:view'
  );

-- 内容编辑：显式 IN 列表
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 3, id FROM sys_permission
WHERE is_deleted = 0
  AND perm_code IN (
    'dashboard:view',
    'content:news:list', 'content:news:edit',
    'content:banner:manage', 'content:media:manage',
    'food:library:manage'
  );

INSERT INTO food_category (id, category_name, sort_no, is_enabled) VALUES
(1, '主食类', 1, 1), (2, '肉蛋类', 2, 1), (3, '蔬菜类', 3, 1),
(4, '水果类', 4, 1), (5, '奶类', 5, 1), (6, '豆制品', 6, 1),
(7, '坚果类', 7, 1), (8, '饮料类', 8, 1), (9, '零食及其他', 9, 1);

INSERT INTO food_nutrition (food_name, category_code, category_id, carbs_per_100g, calories_per_100g) VALUES
('米饭', 1, 1, 25.9, 116), ('燕麦', 1, 1, 66.3, 367), ('面条', 1, 1, 55.0, 284), ('馒头', 1, 1, 47.0, 221),
('鸡胸肉', 2, 2, 0, 133), ('鸡蛋', 2, 2, 1.1, 144), ('瘦猪肉', 2, 2, 0, 143),
('西兰花', 3, 3, 4.3, 34), ('黄瓜', 3, 3, 2.4, 16), ('西红柿', 3, 3, 3.3, 19),
('苹果', 4, 4, 13.7, 52), ('香蕉', 4, 4, 22.8, 89),
('牛奶', 5, 5, 4.8, 54), ('酸奶', 5, 5, 9.5, 72),
('豆腐', 6, 6, 2.6, 81), ('豆浆', 6, 6, 1.1, 14),
('杏仁', 7, 7, 9.5, 578), ('核桃', 7, 7, 9.6, 646),
('矿泉水', 8, 8, 0, 0), ('无糖豆浆', 8, 8, 1.1, 14),
('苏打饼干', 9, 9, 70.0, 433);

INSERT INTO sys_config (config_key, config_value, value_type, config_group, remark) VALUES
('glucose.target.low', '3.9', 2, 'GLUCOSE', '目标区间下限mmol/L'),
('glucose.target.high', '7.8', 2, 'GLUCOSE', '餐后目标上限mmol/L'),
('glucose.danger.high', '10', 2, 'GLUCOSE', '需关注阈值mmol/L'),
('appearance.theme', '1', 2, 'APPEARANCE', '默认主题1绿2青绿3简洁'),
('appearance.fontSize', '2', 2, 'APPEARANCE', '默认字号1标准2较大3更大'),
('notify.glucose', '1', 3, 'NOTIFY', '血糖提醒默认开关'),
('notify.meal', '1', 3, 'NOTIFY', '饮食提醒默认开关'),
('notify.news', '0', 3, 'NOTIFY', '资讯推送默认开关'),
('text.disclaimer', '本系统提供的血糖估算、饮食建议与 AI 问答仅供健康管理参考，不构成医疗诊断或治疗建议；如有不适请及时就医。', 1, 'TEXT', '免责声明'),
('text.about', '控糖健康管理系统（AI控糖助手）由贵州慧科未来科技有限公司运营。', 1, 'TEXT', '关于我们'),
('push.wechat.enabled', '0', 3, 'PUSH', '微信订阅消息总开关'),
('ai.chat.enabled', '1', 3, 'AI', '语音助手总开关');

INSERT INTO cms_banner (title, subtitle, sort_no) VALUES
('慧科未来', '移动互联网 + 健康管理', 1),
('AI 控糖助手', '记录 · 分析 · 建议 · 追踪', 2),
('九时段精准记录', '拍照识别 · 智能分析', 3),
('饮食两段式预测', '科学控糖 · 健康生活', 4);

INSERT INTO cms_news (title, summary_text, channel_code, content_type, video_url, content_body, publish_at, publish_status) VALUES
('餐后血糖为什么会飙升？三步稳住波动', '餐后血糖波动常见于精制主食过量与进餐过快。', 4, 1, NULL, '正文示例', UTC_TIMESTAMP(3), 1),
('指血与 CGM 怎么配合使用更省心', '指血校准准、CGM 看趋势。', 3, 2, 'https://example.com/demo.mp4', '视频说明', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 DAY), 1),
('慧科未来发布 AI 控糖助手 2.0', '全新九时段记录与饮食两段式预测功能上线。', 1, 1, NULL, '正文示例', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 3 DAY), 1),
('2026 糖尿病管理行业白皮书解读', '数字化健康管理成为行业新趋势。', 2, 1, NULL, '正文示例', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 7 DAY), 1);

INSERT INTO ai_model_config (model_code, model_name, provider_code, api_base_url, model_endpoint, is_default, is_enabled, remark) VALUES
('default_chat', '默认对话模型', 1, NULL, 'glucopilot-chat-v1', 1, 1, '部署前请配置 api_key_cipher 与 api_base_url');

INSERT INTO ai_prompt_template (scene_code, template_name, prompt_body, model_code, version_no, is_enabled) VALUES
('chat_system', '控糖助手系统提示', '你是AI控糖助手，回答需结合用户血糖与饮食记录，并声明仅供参考、不构成医疗诊断。', 'default_chat', 1, 1),
('meal_analysis', '饮食分析提示', '根据餐食识别结果评估碳水与血糖风险，给出可执行建议。', 'default_chat', 1, 1),
('glucose_advice', '血糖建议提示', '根据近期血糖节点给出生活方式建议，避免绝对化医疗结论。', 'default_chat', 1, 1);

INSERT INTO msg_push_template (template_code, template_name, channel_type, title, content_body, is_enabled) VALUES
('glucose_remind', '血糖记录提醒', 1, '该测血糖啦', '您好，请完成本日{{node_name}}血糖记录。', 1),
('news_notify', '资讯推送', 1, '控糖新资讯', '{{title}}，点击查看详情。', 1);
