-- ③ 期：AI 长期记忆与合规（管理端 4.14/4.15 权限点与菜单 + 审计查询页入口）。
--
-- 三张表**都已存在**（V1 建的），本次只补"权限与菜单"，不重建表：
--   * chat_user_memory —— 跨会话长期记忆（memory_key/memory_text/confidence/source_session_id）；
--   * ops_audit_log    —— 运营操作审计（只追加，无 gmt_modified）；
--   * chat_context     —— 窗口切片/分片审计（③ 期起记录"每轮 prompt 由哪些分片组成"的元数据）。
--
-- 菜单编号取 2030+（2020~2025 已用于提示词/场景/模型，2013 及以下被看板/资讯等占用）。
-- 权限点：ai:memory:view（4.14 查）、ai:memory:manage（4.15 删）、ai:audit:view（审计查询页）。

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2030, '用户AI长期记忆', 2009, 1, '', NULL, NULL, '', 1, 0, 'F', '0', '0',
       'ai:memory:view', '#', 'migration', NOW(), '用户列表页抽屉：查看某用户的跨会话长期记忆（4.14）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2030);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2031, '用户AI记忆删除', 2030, 1, '', NULL, NULL, '', 1, 0, 'F', '0', '0',
       'ai:memory:manage', '#', 'migration', NOW(), '删除某用户全部长期记忆（4.15），会写审计'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2031);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2032, 'AI 审计日志', 0, 17, '/system/audit-logs', NULL, NULL, '', 1, 0, 'C', '0', '0',
       'ai:audit:view', 'Document', 'migration', NOW(), 'AI 模块操作审计查询（记忆查询/删除、提示词/场景/模型变更）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2032);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM sys_role r
  JOIN sys_menu m ON m.menu_id IN (2030, 2031, 2032)
 WHERE r.role_key IN ('super_admin', 'operator')
   AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

-- ---------------------------------------------------------------------------
-- 播种 MEMORY 场景（长期记忆抽取）的提示词 v1（内容同 classpath prompts/memory/extract.md）
-- BINARY 比较：scene_code 的排序规则大小写不敏感，存在性判断必须 BINARY（V20 的教训）
-- ---------------------------------------------------------------------------
INSERT INTO ai_prompt_template (scene_code, template_name, prompt_body, variables_json, model_code,
                               version_no, is_enabled, change_note, config_version)
SELECT 'MEMORY', '长期记忆抽取提示', '# 角色：长期记忆抽取器
你的输入是**用户与控糖助手之间的一段对话原文**（可能还包含"已知的长期记忆"）。你的任务是从中挑出**值得长期记住的用户事实**，输出 JSON。

## 只提取这五类（键固定，不要自创）
1. diet_taboo：忌口、过敏、明确不吃的食物（例：对花生过敏、不吃动物内脏）；
2. glucose_goal：用户自己说过的血糖目标或医生给的目标（例：想把餐后控制到 8 以内）；
3. health_context：长期健康状况与背景（例：2 型糖尿病确诊 3 年、有高血压）；
4. habit：稳定的生活/运动/记录习惯（例：晚饭后固定散步 30 分钟、习惯早上测空腹血糖）；
5. preference：稳定的沟通偏好（例：希望回答简短、不要反复提醒就医）。

## 绝对不要提取
1. 单次的数据（某天的血糖值、某顿饭的内容）——那是会话内容，不是长期记忆；
2. 助手说过的话、建议、结论；
3. 诊断、用药剂量、调药方案：**一个字都不要写进记忆**（这类内容只能留在对话里并引导就医）；
4. 推测出来的信息：用户没说过的，不要替他补全；
5. 隐私标识信息：身份证、手机号、地址、单位名称等。

## 置信度 confidence（0 到 100 的整数）
- 用户**明确陈述**（"我不吃花生""医生让我控到 8 以内"）：90~100；
- 用户**间接表达但很清楚**（"我一直都是晚上散步"）：70~89；
- 需要推断、或只提过一次的偏好：60~69；
- 拿不准的一律**不要输出**这一条，而不是给低分硬塞。

## 输出格式
只输出一个 JSON 对象，不要 Markdown 代码块围栏、不要任何解释文字：
{"memories":[{"key":"diet_taboo","text":"对花生过敏","confidence":95,"evidence":"用户说吃了花生会起疹子"}]}

- 没有可提取内容时输出 {"memories":[]}；
- 每条 text 不超过 60 字，用陈述句，主语是用户（例："对花生过敏"而不是"用户说他对花生过敏"）；
- 同一个 key 只输出**最可靠的一条**（如果已有记忆与新内容冲突，输出新的那条，并在 text 里写明更正后的说法）；
- 不要输出花括号以外的多余符号，不要用 Markdown。', '[]', NULL, 1, 1, 'V24：③ 期长期记忆抽取场景的初始版本', 1
 WHERE NOT EXISTS (SELECT 1 FROM ai_prompt_template
                    WHERE BINARY scene_code = 'MEMORY' AND version_no = 1 AND is_deleted = 0);
