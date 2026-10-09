-- Flyway V4: RuoYi complete administration, monitor, scheduler and generator modules.
-- Existing ops_account/sys_role/sys_permission tables remain the single source of truth for B-end login and RBAC.
SET NAMES utf8mb4;

CREATE TABLE sys_dept (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  ancestors VARCHAR(512) NOT NULL DEFAULT '0',
  dept_name VARCHAR(64) NOT NULL,
  sort_no INT UNSIGNED NOT NULL DEFAULT 0,
  leader VARCHAR(64) NULL,
  phone VARCHAR(20) NULL,
  email VARCHAR(128) NULL,
  dept_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_dept_name_active (parent_id, dept_name, is_deleted),
  KEY idx_sys_dept_parent (parent_id),
  CONSTRAINT chk_sys_dept_status CHECK (dept_status IN (0, 1)),
  CONSTRAINT chk_sys_dept_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依部门表';

CREATE TABLE sys_post (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  post_code VARCHAR(64) NOT NULL,
  post_name VARCHAR(64) NOT NULL,
  post_sort INT UNSIGNED NOT NULL DEFAULT 0,
  post_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_post_code_active (post_code, is_deleted),
  UNIQUE KEY uk_sys_post_name_active (post_name, is_deleted),
  CONSTRAINT chk_sys_post_status CHECK (post_status IN (0, 1)),
  CONSTRAINT chk_sys_post_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依岗位表';

CREATE TABLE sys_ops_post (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ops_id BIGINT UNSIGNED NOT NULL,
  post_id BIGINT UNSIGNED NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_ops_post_active (ops_id, post_id, is_deleted),
  KEY idx_sys_ops_post_ops (ops_id),
  KEY idx_sys_ops_post_post (post_id),
  CONSTRAINT chk_sys_ops_post_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营用户岗位关联表';

ALTER TABLE ops_profile
  ADD COLUMN dept_id BIGINT UNSIGNED NULL COMMENT '若依部门ID' AFTER ops_id;

CREATE TABLE sys_menu (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  menu_name VARCHAR(64) NOT NULL,
  parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  sort_no INT UNSIGNED NOT NULL DEFAULT 0,
  path_url VARCHAR(256) NULL,
  component_name VARCHAR(256) NULL,
  query_string VARCHAR(256) NULL,
  is_frame TINYINT UNSIGNED NOT NULL DEFAULT 1,
  is_cache TINYINT UNSIGNED NOT NULL DEFAULT 0,
  menu_type CHAR(1) NOT NULL DEFAULT 'C',
  visible TINYINT UNSIGNED NOT NULL DEFAULT 1,
  menu_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  perm_code VARCHAR(128) NULL,
  icon_name VARCHAR(64) NULL,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_sys_menu_parent (parent_id),
  KEY idx_sys_menu_perm (perm_code),
  CONSTRAINT chk_sys_menu_type CHECK (menu_type IN ('M', 'C', 'F')),
  CONSTRAINT chk_sys_menu_flags CHECK (is_frame IN (0, 1) AND is_cache IN (0, 1) AND visible IN (0, 1) AND menu_status IN (0, 1)),
  CONSTRAINT chk_sys_menu_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依动态菜单表';

CREATE TABLE sys_dict_type (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dict_name VARCHAR(100) NOT NULL,
  dict_type VARCHAR(100) NOT NULL,
  dict_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_dict_type_active (dict_type, is_deleted),
  CONSTRAINT chk_sys_dict_type_status CHECK (dict_status IN (0, 1)),
  CONSTRAINT chk_sys_dict_type_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依字典类型表';

CREATE TABLE sys_dict_data (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dict_sort INT UNSIGNED NOT NULL DEFAULT 0,
  dict_label VARCHAR(100) NOT NULL,
  dict_value VARCHAR(100) NOT NULL,
  dict_type VARCHAR(100) NOT NULL,
  css_class VARCHAR(100) NULL,
  list_class VARCHAR(100) NULL,
  is_default TINYINT UNSIGNED NOT NULL DEFAULT 0,
  dict_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_dict_data_active (dict_type, dict_value, is_deleted),
  KEY idx_sys_dict_data_type (dict_type),
  CONSTRAINT chk_sys_dict_data_flags CHECK (is_default IN (0, 1) AND dict_status IN (0, 1)),
  CONSTRAINT chk_sys_dict_data_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依字典数据表';

CREATE TABLE sys_notice (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  notice_title VARCHAR(100) NOT NULL,
  notice_type CHAR(1) NOT NULL DEFAULT '1',
  notice_content TEXT NOT NULL,
  notice_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_sys_notice_status (notice_status),
  CONSTRAINT chk_sys_notice_type CHECK (notice_type IN ('1', '2')),
  CONSTRAINT chk_sys_notice_status CHECK (notice_status IN (0, 1)),
  CONSTRAINT chk_sys_notice_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依通知公告表';

CREATE TABLE sys_login_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL DEFAULT '',
  login_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  login_message VARCHAR(255) NOT NULL DEFAULT '',
  client_ip VARCHAR(45) NULL,
  user_agent VARCHAR(512) NULL,
  login_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sys_login_log_name_time (username, login_at),
  KEY idx_sys_login_log_status_time (login_status, login_at),
  CONSTRAINT chk_sys_login_log_status CHECK (login_status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依登录日志表';

CREATE TABLE sys_oper_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ops_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  title VARCHAR(64) NOT NULL DEFAULT '',
  business_type VARCHAR(32) NOT NULL DEFAULT '',
  method_name VARCHAR(512) NULL,
  request_method VARCHAR(16) NULL,
  operator_name VARCHAR(64) NOT NULL DEFAULT '',
  request_url VARCHAR(512) NULL,
  client_ip VARCHAR(45) NULL,
  request_param TEXT NULL,
  response_result TEXT NULL,
  oper_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  error_message TEXT NULL,
  oper_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sys_oper_log_ops_time (ops_id, oper_at),
  KEY idx_sys_oper_log_title_time (title, oper_at),
  CONSTRAINT chk_sys_oper_log_status CHECK (oper_status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依操作日志表';

CREATE TABLE sys_job (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_name VARCHAR(64) NOT NULL,
  job_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
  invoke_target VARCHAR(128) NOT NULL,
  cron_expression VARCHAR(64) NOT NULL,
  misfire_policy VARCHAR(20) NOT NULL DEFAULT 'DO_NOTHING',
  concurrent_allowed TINYINT UNSIGNED NOT NULL DEFAULT 0,
  job_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_job_name_group_active (job_name, job_group, is_deleted),
  CONSTRAINT chk_sys_job_flags CHECK (concurrent_allowed IN (0, 1) AND job_status IN (0, 1)),
  CONSTRAINT chk_sys_job_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依定时任务表';

CREATE TABLE sys_job_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_id BIGINT UNSIGNED NOT NULL,
  job_name VARCHAR(64) NOT NULL,
  job_group VARCHAR(64) NOT NULL,
  invoke_target VARCHAR(128) NOT NULL,
  job_message VARCHAR(1000) NULL,
  job_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  exception_info TEXT NULL,
  start_at DATETIME(3) NOT NULL,
  end_at DATETIME(3) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sys_job_log_job_time (job_id, start_at),
  CONSTRAINT chk_sys_job_log_status CHECK (job_status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依任务执行日志表';

CREATE TABLE gen_table (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  table_name VARCHAR(128) NOT NULL,
  table_comment VARCHAR(500) NULL,
  class_name VARCHAR(128) NULL,
  template_category VARCHAR(32) NOT NULL DEFAULT 'crud',
  package_name VARCHAR(256) NULL,
  module_name VARCHAR(64) NULL,
  business_name VARCHAR(64) NULL,
  function_name VARCHAR(128) NULL,
  function_author VARCHAR(64) NULL,
  gen_options VARCHAR(1000) NULL,
  remark VARCHAR(256) NULL,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gen_table_name_active (table_name, is_deleted),
  CONSTRAINT chk_gen_table_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依代码生成业务表';

CREATE TABLE gen_table_column (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  table_id BIGINT UNSIGNED NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  column_comment VARCHAR(500) NULL,
  column_type VARCHAR(128) NOT NULL,
  java_type VARCHAR(64) NOT NULL,
  java_field VARCHAR(128) NOT NULL,
  is_pk TINYINT UNSIGNED NOT NULL DEFAULT 0,
  is_required TINYINT UNSIGNED NOT NULL DEFAULT 0,
  is_insertable TINYINT UNSIGNED NOT NULL DEFAULT 1,
  is_editable TINYINT UNSIGNED NOT NULL DEFAULT 1,
  is_listable TINYINT UNSIGNED NOT NULL DEFAULT 1,
  is_queryable TINYINT UNSIGNED NOT NULL DEFAULT 0,
  query_type VARCHAR(32) NOT NULL DEFAULT 'EQ',
  html_type VARCHAR(32) NOT NULL DEFAULT 'input',
  dict_type VARCHAR(100) NULL,
  sort_no INT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gen_table_column_active (table_id, column_name, is_deleted),
  CONSTRAINT chk_gen_table_column_flags CHECK (is_pk IN (0, 1) AND is_required IN (0, 1) AND is_insertable IN (0, 1) AND is_editable IN (0, 1) AND is_listable IN (0, 1) AND is_queryable IN (0, 1)),
  CONSTRAINT chk_gen_table_column_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='若依代码生成字段表';

INSERT INTO sys_dept (id, parent_id, ancestors, dept_name, sort_no, leader, dept_status, remark)
SELECT 1, 0, '0', 'GlucoPilot', 0, '系统管理员', 1, '若依系统管理根部门'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 1 AND is_deleted = 0);

INSERT INTO sys_post (id, post_code, post_name, post_sort, post_status, remark)
SELECT 1, 'administrator', '系统管理员', 1, 1, 'RuoYi 系统管理员岗位'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_post WHERE post_code = 'administrator' AND is_deleted = 0);

INSERT INTO sys_dict_type (dict_name, dict_type, dict_status, remark)
SELECT '是否状态', 'sys_yes_no', 1, '通用状态字典'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'sys_yes_no' AND is_deleted = 0);
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, list_class, is_default, dict_status)
SELECT 1, '是', '1', 'sys_yes_no', 'success', 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'sys_yes_no' AND dict_value = '1' AND is_deleted = 0);
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, list_class, is_default, dict_status)
SELECT 2, '否', '0', 'sys_yes_no', 'info', 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'sys_yes_no' AND dict_value = '0' AND is_deleted = 0);

INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id, path_url, sort_no, perm_status)
SELECT x.perm_code, x.perm_name, 1, 0, x.path_url, x.sort_no, 1
FROM (
  SELECT 'system:user:manage' perm_code, '用户管理' perm_name, '/system/users' path_url, 101 sort_no
  UNION ALL SELECT 'system:menu:manage', '菜单管理', '/system/menus', 102
  UNION ALL SELECT 'system:dept:manage', '部门管理', '/system/depts', 103
  UNION ALL SELECT 'system:post:manage', '岗位管理', '/system/posts', 104
  UNION ALL SELECT 'system:dict:manage', '字典管理', '/system/dicts', 105
  UNION ALL SELECT 'system:notice:manage', '通知公告', '/system/notices', 106
  UNION ALL SELECT 'monitor:online:list', '在线用户', '/monitor/online', 201
  UNION ALL SELECT 'monitor:logininfor:list', '登录日志', '/monitor/login-logs', 202
  UNION ALL SELECT 'monitor:operlog:list', '操作日志', '/monitor/oper-logs', 203
  UNION ALL SELECT 'monitor:server:list', '服务监控', '/monitor/server', 204
  UNION ALL SELECT 'monitor:cache:manage', '缓存监控', '/monitor/cache', 205
  UNION ALL SELECT 'monitor:job:manage', '定时任务', '/monitor/jobs', 206
  UNION ALL SELECT 'tool:gen:manage', '代码生成', '/tool/generator', 301
) x
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.perm_code = x.perm_code AND p.is_deleted = 0);

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.is_deleted = 0
WHERE r.role_code = 'super_admin' AND r.is_deleted = 0
  AND p.perm_code IN ('system:user:manage', 'system:menu:manage', 'system:dept:manage', 'system:post:manage', 'system:dict:manage', 'system:notice:manage', 'monitor:online:list', 'monitor:logininfor:list', 'monitor:operlog:list', 'monitor:server:list', 'monitor:cache:manage', 'monitor:job:manage', 'tool:gen:manage')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.perm_id = p.id AND rp.is_deleted = 0);

INSERT INTO sys_menu (id, menu_name, parent_id, sort_no, path_url, component_name, menu_type, perm_code, icon_name, remark)
SELECT 100, '系统管理', 0, 100, NULL, NULL, 'M', NULL, 'Setting', 'RuoYi 系统管理目录'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 100 AND is_deleted = 0);
INSERT INTO sys_menu (id, menu_name, parent_id, sort_no, path_url, component_name, menu_type, perm_code, icon_name)
SELECT x.id, x.menu_name, 100, x.sort_no, x.path_url, x.component_name, 'C', x.perm_code, x.icon_name
FROM (
  SELECT 101 id, '用户管理' menu_name, 1 sort_no, '/system/users' path_url, 'system/users' component_name, 'system:user:manage' perm_code, 'User' icon_name
  UNION ALL SELECT 102, '角色管理', 2, '/system/roles', 'system/roles', 'system:role:manage', 'UserFilled'
  UNION ALL SELECT 103, '菜单管理', 3, '/system/menus', 'system/menus', 'system:menu:manage', 'Menu'
  UNION ALL SELECT 104, '部门管理', 4, '/system/depts', 'system/depts', 'system:dept:manage', 'OfficeBuilding'
  UNION ALL SELECT 105, '岗位管理', 5, '/system/posts', 'system/posts', 'system:post:manage', 'Postcard'
  UNION ALL SELECT 106, '字典管理', 6, '/system/dicts', 'system/dicts', 'system:dict:manage', 'Collection'
  UNION ALL SELECT 107, '系统参数', 7, '/system/config', 'system/config', 'system:config:manage', 'Setting'
  UNION ALL SELECT 108, '通知公告', 8, '/system/notices', 'system/notices', 'system:notice:manage', 'Bell'
) x WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = x.id AND m.is_deleted = 0);
INSERT INTO sys_menu (id, menu_name, parent_id, sort_no, path_url, component_name, menu_type, perm_code, icon_name, remark)
SELECT 200, '系统监控', 0, 200, NULL, NULL, 'M', NULL, 'Monitor', 'RuoYi 系统监控目录'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 200 AND is_deleted = 0);
INSERT INTO sys_menu (id, menu_name, parent_id, sort_no, path_url, component_name, menu_type, perm_code, icon_name)
SELECT x.id, x.menu_name, 200, x.sort_no, x.path_url, x.component_name, 'C', x.perm_code, x.icon_name
FROM (
  SELECT 201 id, '在线用户' menu_name, 1 sort_no, '/monitor/online' path_url, 'monitor/online' component_name, 'monitor:online:list' perm_code, 'User' icon_name
  UNION ALL SELECT 202, '登录日志', 2, '/monitor/login-logs', 'monitor/login-logs', 'monitor:logininfor:list', 'Tickets'
  UNION ALL SELECT 203, '操作日志', 3, '/monitor/oper-logs', 'monitor/oper-logs', 'monitor:operlog:list', 'Document'
  UNION ALL SELECT 204, '服务监控', 4, '/monitor/server', 'monitor/server', 'monitor:server:list', 'Cpu'
  UNION ALL SELECT 205, '缓存监控', 5, '/monitor/cache', 'monitor/cache', 'monitor:cache:manage', 'Coin'
  UNION ALL SELECT 206, '定时任务', 6, '/monitor/jobs', 'monitor/jobs', 'monitor:job:manage', 'Clock'
) x WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = x.id AND m.is_deleted = 0);
INSERT INTO sys_menu (id, menu_name, parent_id, sort_no, path_url, component_name, menu_type, perm_code, icon_name)
SELECT 300, '代码生成', 0, 300, '/tool/generator', 'tool/generator', 'C', 'tool:gen:manage', 'DocumentAdd'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 300 AND is_deleted = 0);
