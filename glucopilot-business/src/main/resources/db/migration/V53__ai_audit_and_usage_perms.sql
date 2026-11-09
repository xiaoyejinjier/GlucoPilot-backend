-- AI 侧权限颗粒度：把「会话审计」与「用量报表」从 ai:model:query 里拆出来。
--
-- 背景：ai:model:query 原先一个权限点横跨四类接口——模型列表/详情（配置面）、
-- /ai/runtime/status（诊断面）、/ai/usage（成本面）、/ai/chat-sessions/{id}/context（对话内容面）。
-- 只要想让某个人「能看模型清单」，就同时给了「能读全部用户对话原文」的能力，这与最小权限相反。
--
-- 本次拆成两个新权限点：
--   ai:chat:audit  会话审计（列表 / 消息 / 分片），内容含用户与模型的原始对话 → 只给超管
--   ai:usage:view  用量报表（天 × 模型的 token 汇总，不含任何对话内容）
-- 保留在 ai:model:query 下的：模型列表/详情、/ai/runtime/status（它只描述"配置是否自洽"，不含对话数据）。
--
-- 授予策略（刻意的选择）：
--   1) 超管不插 sys_role_menu —— 它的权限来自 RuoYi 的 `*:*:*` 短路分支，插行只会制造重复真相；
--   2) ai:usage:view 授予运营（role_id=2）：此前运营通过 ai:model:query 已经能看到用量页，
--      拆点不能变成"悄悄收走既有能力"；
--   3) ai:chat:audit 不授予任何角色：它读的是用户对话原文，默认最小权限，需要时在角色管理里手工勾选。
--
-- 菜单号 2034/2035 避开已占用的 2000-2013、2020-2027、2030-2033。全部按 menu_id 做幂等守卫。

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2034, 'AI 会话审计', 0, 17, '/ai/chat-sessions', NULL, NULL, '', 1, 0, 'C', '0', '0',
       'ai:chat:audit', 'ChatDotSquare', 'migration', NOW(),
       '会话列表 + 每轮消息与 token 分片（只读；含用户与模型的原始对话，默认只给超管）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2034);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2035, 'AI 用量', 0, 18, '/ai/usage', NULL, NULL, '', 1, 0, 'C', '0', '0',
       'ai:usage:view', 'DataLine', 'migration', NOW(),
       '按天 × 模型的 token 用量报表（只读；不包含任何对话内容）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2035);

-- 运营：保留既有能力（拆点前它通过 ai:model:query 就能看用量页）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 2, 2035 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 2 AND menu_id = 2035);
