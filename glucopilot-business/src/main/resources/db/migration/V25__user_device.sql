-- C 端用户设备档案 + 管理端 device:manage 权限。

CREATE TABLE user_device (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '设备ID',
  device_sn       VARCHAR(64)     NOT NULL COMMENT '设备序列号',
  device_name     VARCHAR(64)     NOT NULL COMMENT '设备名称',
  provider        VARCHAR(32)     NOT NULL DEFAULT 'MOCK' COMMENT '厂商编码 MOCK/厂商',
  user_id         BIGINT UNSIGNED NULL COMMENT '绑定 C 端用户ID，可空未绑定',
  device_status   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0禁用1正常',
  last_sync_at    DATETIME(3)     NULL COMMENT '最近同步时间',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_sn_active (device_sn, is_deleted),
  KEY idx_user (user_id),
  KEY idx_status (device_status),
  CONSTRAINT chk_ud_sn CHECK (device_sn <> ''),
  CONSTRAINT chk_ud_name CHECK (device_name <> ''),
  CONSTRAINT chk_ud_provider CHECK (provider <> ''),
  CONSTRAINT chk_ud_status CHECK (device_status IN (0, 1)),
  CONSTRAINT chk_ud_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_ud_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户设备档案表';

-- 官方若依 sys_menu：权限标识供 @ss.hasPermi 使用；管理端侧栏仍由前端写死路由。
INSERT INTO sys_menu (
  menu_name, parent_id, order_num, path, component, query, route_name,
  is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark
)
SELECT
  '设备管理', 0, 20, 'devices', NULL, NULL, '',
  1, 0, 'C', '0', '0', 'device:manage', 'cpu', 'system', NOW(), 'C端设备档案管理'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'device:manage');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.role_key = 'super_admin'
  AND m.perms = 'device:manage'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id
  );

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.role_key = 'operator'
  AND m.perms = 'device:manage'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id
  );
