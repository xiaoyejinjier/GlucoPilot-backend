-- AI 审计扩展：把菜单 2034 从「会话审计」升级为「AI 审计」聚合页，并把权限点语义写进 remark。
--
-- 背景：V53 只为会话审计开了菜单 2034（/ai/chat-sessions）。此后审计面扩到 6 类数据
-- （工具调用、长期记忆、滚动摘要、内容审核命中、中英翻译、多模态任务），前端改成
-- 一个页面 + 多个页签，路径也随之变成 /ai/audit。
--
-- 为什么改菜单行而不是新增一行：
--   * 权限点没变（仍是 ai:chat:audit），改一行能让「已有授权的角色」自动指向新页面，
--     新增一行反而会留下一个打不开的旧入口；
--   * 菜单是运营看到的产品结构，不是历史档案——旧路径没有兼容包袱（前端没有外部链接进来）。
--
-- 幂等：按 menu_id = 2034 更新；若该行不存在（在只跑新库的环境里），补插一行。

UPDATE sys_menu
   SET menu_name = 'AI 审计',
       path      = '/ai/audit',
       icon      = 'DocumentChecked',
       order_num = 17,
       remark    = '会话 / 工具调用 / 长期记忆 / 滚动摘要 / 内容审核命中 / 中英翻译 / 多模态任务（全部只读）'
 WHERE menu_id = 2034;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2034, 'AI 审计', 0, 17, '/ai/audit', NULL, NULL, '', 1, 0, 'C', '0', '0',
       'ai:chat:audit', 'DocumentChecked', 'migration', NOW(),
       '会话 / 工具调用 / 长期记忆 / 滚动摘要 / 内容审核命中 / 中英翻译 / 多模态任务（全部只读）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2034);
