-- 用户注销全链路审计 + 管理端「取消注销」权限。
--
-- 背景：user_account_cancellation 只记录申请/执行/恢复事实，APPLY、USER_RECOVER、
-- SYSTEM_PURGE 没有独立的合规留痕；管理端恢复需要一个只有超管持有的最小权限点。
--
-- 事件：APPLY / USER_RECOVER / ADMIN_RECOVER / SYSTEM_PURGE
-- 纪律：本表只 INSERT，不 UPDATE/DELETE；remark 禁止写入任何健康内容。
-- 保留：不短于 3 年，只归档不物理删除（归档前冷备）。

CREATE TABLE user_cancel_logs (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID(user_account.id)',
  cancellation_id BIGINT UNSIGNED NULL COMMENT '注销凭据ID(user_account_cancellation.id)',
  action_type     VARCHAR(32)     NOT NULL COMMENT 'APPLY/USER_RECOVER/ADMIN_RECOVER/SYSTEM_PURGE',
  from_status     TINYINT UNSIGNED NULL COMMENT '变更前账号状态',
  to_status       TINYINT UNSIGNED NULL COMMENT '变更后账号状态',
  operator_type   VARCHAR(16)     NOT NULL COMMENT 'USER/ADMIN/SYSTEM',
  operator_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作人：用户ID/运营账号ID/0',
  remark          VARCHAR(512)    NULL COMMENT '备注（不含健康内容）',
  request_id      VARCHAR(64)     NULL COMMENT 'X-Request-Id，便于与网关/应用日志串联',
  client_ip       VARCHAR(45)     NULL COMMENT '客户端IP',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  CONSTRAINT pk_ucl_id PRIMARY KEY (id),
  -- MySQL 唯一索引对 NULL 不生效；APPLY 等事件必须带 cancellation_id 才能防重。
  UNIQUE KEY uk_ucl_cancel_action (cancellation_id, action_type),
  KEY idx_ucl_user_time (user_id, created_at),
  KEY idx_ucl_action_time (action_type, created_at),
  CONSTRAINT chk_ucl_action CHECK (action_type IN
    ('APPLY', 'USER_RECOVER', 'ADMIN_RECOVER', 'SYSTEM_PURGE')),
  CONSTRAINT chk_ucl_operator CHECK (operator_type IN ('USER', 'ADMIN', 'SYSTEM')),
  CONSTRAINT chk_ucl_from CHECK (from_status IS NULL OR from_status BETWEEN 0 AND 4),
  CONSTRAINT chk_ucl_to CHECK (to_status IS NULL OR to_status BETWEEN 0 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='账号注销全链路审计日志(只追加)';

-- ---------------------------------------------------------------------------
-- 权限点：取消用户注销（仅恢复，不含强制注销/清理）
-- 菜单编号 2040：2009 为用户管理目录，2030/2031 已被 AI 长期记忆占用。
-- 只授予 super_admin：注销恢复直接改变账号可用性，风险高于记忆管理。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2040, '取消用户注销', 2009, 2, '', NULL, NULL, '', 1, 0, 'F', '0', '0',
       'user:cancel:recover', '#', 'migration', NOW(), '用户管理页：将注销中(4)用户恢复为 pre_status，写审计'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2040)
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'user:cancel:recover');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM sys_role r
  CROSS JOIN sys_menu m
 WHERE r.role_key = 'super_admin'
   AND m.perms = 'user:cancel:recover'
   AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);
