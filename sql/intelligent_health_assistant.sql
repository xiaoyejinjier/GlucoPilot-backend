-- =============================================================================
-- AI 控糖助手 · 生产库 DDL（intelligent_health_assistant）
-- 日常开发请用 Flyway：backend/src/main/resources/db/migration/（启动后端自动迁移）
-- 本文件用于整库对照/手工 DROP+重建；内容与 V1+V2 迁移对齐。
-- 版本：v2.1（对齐《数据库设计文档》20260914-01-02 · 38 表）
-- 设计依据：docs/数据库设计文档.docx（生产基线 merged-v2）
--
-- 规范摘要：
-- 1. 审计字段 gmt_create / gmt_modified（DATETIME(3)）；逻辑删除 is_deleted
-- 2. 唯一索引 (业务键, is_deleted)；不使用 VIRTUAL 生成列
-- 3. 无物理外键、无触发器、无存储过程；关联由应用层校验
-- 4. TINYINT UNSIGNED + COMMENT 枚举；血糖/碳水/热量用 DECIMAL
-- 5. ops_audit_log 只追加（无 gmt_modified）
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS sugar_health
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE sugar_health;

-- ---------- 逆序删除（可重复执行）----------
DROP TABLE IF EXISTS msg_push_record;
DROP TABLE IF EXISTS msg_push_task;
DROP TABLE IF EXISTS msg_push_template;
DROP TABLE IF EXISTS ai_prompt_template;
DROP TABLE IF EXISTS ai_model_config;
DROP TABLE IF EXISTS ops_audit_log;
DROP TABLE IF EXISTS sys_config;
DROP TABLE IF EXISTS auth_sms_code;
DROP TABLE IF EXISTS ocr_task;
DROP TABLE IF EXISTS chat_user_memory;
DROP TABLE IF EXISTS chat_context;
DROP TABLE IF EXISTS chat_context_snapshot;
DROP TABLE IF EXISTS meal_food_item;
DROP TABLE IF EXISTS news_user_action;
DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS chat_session;
DROP TABLE IF EXISTS glucose_record;
DROP TABLE IF EXISTS meal_record;
DROP TABLE IF EXISTS data_export_task;
DROP TABLE IF EXISTS data_backup;
DROP TABLE IF EXISTS feedback;
DROP TABLE IF EXISTS media_file;
DROP TABLE IF EXISTS cms_news;
DROP TABLE IF EXISTS cms_banner;
DROP TABLE IF EXISTS food_nutrition;
DROP TABLE IF EXISTS food_category;
DROP TABLE IF EXISTS user_setting;
DROP TABLE IF EXISTS user_health;
DROP TABLE IF EXISTS user_profile;
DROP TABLE IF EXISTS user_session;
DROP TABLE IF EXISTS user_account;
DROP TABLE IF EXISTS ops_session;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_ops_role;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS ops_profile;
DROP TABLE IF EXISTS ops_account;
DROP TABLE IF EXISTS dict_glucose_node;


-- =============================================================================
-- 一、字典
-- =============================================================================

CREATE TABLE dict_glucose_node (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  node_code     VARCHAR(32)     NOT NULL COMMENT '节点编码',
  node_name     VARCHAR(32)     NOT NULL COMMENT '节点名称',
  short_name    VARCHAR(16)     NOT NULL COMMENT '短名',
  is_meal       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否餐点 0否1是',
  sort_no       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序',
  is_enabled    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用 0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_node_code (node_code, is_deleted),
  KEY idx_sort (sort_no),
  CONSTRAINT chk_dgn_meal CHECK (is_meal IN (0, 1)),
  CONSTRAINT chk_dgn_enabled CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_dgn_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_dgn_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='九时段节点字典';


-- =============================================================================
-- 二、C 端用户（登录 / 资料 / 健康 / 设置 / 会话）
-- =============================================================================

CREATE TABLE user_account (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户账号ID',
  openid          VARCHAR(64)     NOT NULL COMMENT '微信 openid',
  union_id        VARCHAR(64)     NULL COMMENT '微信 unionid',
  phone           VARCHAR(20)     NULL COMMENT '绑定手机号',
  account_status  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0禁用1正常2注销中',
  last_login_at   DATETIME(3)     NULL COMMENT '最近登录时间',
  last_login_ip   VARCHAR(45)     NULL COMMENT '最近登录IP',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_openid_active (openid, is_deleted),
  UNIQUE KEY uk_union_id_active (union_id, is_deleted),
  UNIQUE KEY uk_phone_active (phone, is_deleted),
  KEY idx_status (account_status),
  CONSTRAINT chk_ua_status CHECK (account_status IN (0, 1, 2)),
  CONSTRAINT chk_ua_openid CHECK (openid <> ''),
  CONSTRAINT chk_ua_union_id CHECK (union_id IS NULL OR union_id <> ''),
  CONSTRAINT chk_ua_phone CHECK (phone IS NULL OR phone <> ''),
  CONSTRAINT chk_ua_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_ua_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户登录账号表';

CREATE TABLE user_profile (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资料ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  nickname      VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '昵称',
  avatar_url    VARCHAR(512)    NULL COMMENT '头像URL',
  avatar_text   VARCHAR(8)      NULL COMMENT '默认头像文字',
  gender        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '性别 0未知1男2女',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_active (user_id, is_deleted),
  CONSTRAINT chk_up_gender CHECK (gender IN (0, 1, 2)),
  CONSTRAINT chk_up_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_up_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户基本信息表';

CREATE TABLE user_health (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '健康档案ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  diabetes_type        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '糖尿病类型 0未填1一型2二型3妊娠4其他',
  diabetes_type_text   VARCHAR(32)     NULL COMMENT '类型补充文案',
  diagnosed_ym         CHAR(7)         NULL COMMENT '确诊年月YYYY-MM',
  age                  SMALLINT UNSIGNED NULL COMMENT '年龄',
  medication           VARCHAR(256)    NULL COMMENT '用药说明',
  exercise_habit       VARCHAR(256)    NULL COMMENT '运动习惯',
  target_fasting       DECIMAL(4,1)    NULL COMMENT '空腹目标mmol/L',
  target_postprandial  DECIMAL(4,1)    NULL COMMENT '餐后目标mmol/L',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_active (user_id, is_deleted),
  CONSTRAINT chk_uh_dtype CHECK (diabetes_type IN (0, 1, 2, 3, 4)),
  CONSTRAINT chk_uh_age CHECK (age IS NULL OR age <= 150),
  CONSTRAINT chk_uh_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_uh_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户健康档案表';

CREATE TABLE user_setting (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '设置ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  font_size            TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT '字号 1标准2较大3更大',
  theme_code           TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '主题 1绿2青绿3简洁',
  notify_glucose       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '血糖提醒 0关1开',
  notify_meal          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '饮食提醒 0关1开',
  notify_news          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '资讯提醒 0关1开',
  disclaimer_agreed    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '免责声明 0未同意1已同意',
  disclaimer_version   VARCHAR(32)     NULL COMMENT '免责声明版本号',
  disclaimer_agreed_at DATETIME(3)     NULL COMMENT '同意时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_active (user_id, is_deleted),
  CONSTRAINT chk_us_font CHECK (font_size IN (1, 2, 3)),
  CONSTRAINT chk_us_theme CHECK (theme_code IN (1, 2, 3)),
  CONSTRAINT chk_us_flags CHECK (
    notify_glucose IN (0, 1) AND notify_meal IN (0, 1)
    AND notify_news IN (0, 1) AND disclaimer_agreed IN (0, 1)
  ),
  CONSTRAINT chk_us_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_us_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户偏好设置表';

CREATE TABLE user_session (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  access_token    VARCHAR(512)    NOT NULL COMMENT '访问令牌(JWT或随机串)',
  login_type      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '登录方式1微信2手机号',
  expire_at       DATETIME(3)     NOT NULL COMMENT '过期时间',
  client_ip       VARCHAR(45)     NULL COMMENT '客户端IP',
  user_agent      VARCHAR(512)    NULL COMMENT 'UA（微信开发者工具等环境可能较长）',
  is_valid        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否有效0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_token_active (access_token, is_deleted),
  KEY idx_user_expire (user_id, expire_at),
  CONSTRAINT chk_usess_login CHECK (login_type IN (1, 2)),
  CONSTRAINT chk_usess_valid CHECK (is_valid IN (0, 1)),
  CONSTRAINT chk_usess_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_usess_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端登录会话表';

CREATE TABLE auth_sms_code (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  phone           VARCHAR(20)     NOT NULL COMMENT '手机号',
  verify_code     CHAR(6)         NOT NULL COMMENT '6位验证码',
  expire_at       DATETIME(3)     NOT NULL COMMENT '过期时间',
  is_used         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已使用0否1是',
  client_ip       VARCHAR(45)     NULL COMMENT '请求IP',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_phone_time (phone, gmt_create),
  KEY idx_expire (expire_at),
  CONSTRAINT chk_asc_phone CHECK (phone <> ''),
  CONSTRAINT chk_asc_used CHECK (is_used IN (0, 1)),
  CONSTRAINT chk_asc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_asc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='短信验证码表';


-- =============================================================================
-- 三、运营账号 + RBAC + 会话
-- =============================================================================

CREATE TABLE ops_account (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运营账号ID',
  username        VARCHAR(64)     NOT NULL COMMENT '登录名',
  password_hash   VARCHAR(255)    NOT NULL COMMENT '密码哈希(bcrypt/argon2)',
  account_status  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0禁用1正常',
  last_login_at   DATETIME(3)     NULL COMMENT '最近登录时间',
  last_login_ip   VARCHAR(45)     NULL COMMENT '最近登录IP',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_username_active (username, is_deleted),
  CONSTRAINT chk_oa_status CHECK (account_status IN (0, 1)),
  CONSTRAINT chk_oa_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_oa_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营后台登录账号表';

CREATE TABLE ops_profile (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运营资料ID',
  ops_id        BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  real_name     VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '真实姓名',
  mobile        VARCHAR(20)     NULL COMMENT '手机号',
  email         VARCHAR(128)    NULL COMMENT '邮箱',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_ops_active (ops_id, is_deleted),
  CONSTRAINT chk_op_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_op_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营人员基本信息表';

CREATE TABLE sys_role (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  role_code     VARCHAR(64)     NOT NULL COMMENT '角色编码',
  role_name     VARCHAR(64)     NOT NULL COMMENT '角色名称',
  sort_no       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序',
  role_status   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0停用1启用',
  remark        VARCHAR(256)    NULL COMMENT '备注',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_role_code_active (role_code, is_deleted),
  CONSTRAINT chk_role_status CHECK (role_status IN (0, 1)),
  CONSTRAINT chk_role_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_role_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='系统角色表';

CREATE TABLE sys_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  perm_code     VARCHAR(128)    NOT NULL COMMENT '权限编码',
  perm_name     VARCHAR(64)     NOT NULL COMMENT '权限名称',
  perm_type     TINYINT UNSIGNED NOT NULL COMMENT '类型 1菜单2按钮3接口',
  parent_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父权限ID 0为根',
  path_url      VARCHAR(256)    NULL COMMENT '路由或接口路径',
  sort_no       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序',
  perm_status   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0停用1启用',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code_active (perm_code, is_deleted),
  KEY idx_parent (parent_id),
  CONSTRAINT chk_perm_type CHECK (perm_type IN (1, 2, 3)),
  CONSTRAINT chk_perm_status CHECK (perm_status IN (0, 1)),
  CONSTRAINT chk_perm_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_perm_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='系统权限表';

CREATE TABLE sys_ops_role (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  ops_id        BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  role_id       BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_ops_role_active (ops_id, role_id, is_deleted),
  KEY idx_role (role_id),
  CONSTRAINT chk_sor_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_sor_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营账号角色关联表';

CREATE TABLE sys_role_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  role_id       BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
  perm_id       BIGINT UNSIGNED NOT NULL COMMENT '权限ID',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm_active (role_id, perm_id, is_deleted),
  KEY idx_perm (perm_id),
  CONSTRAINT chk_srp_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_srp_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='角色权限关联表';

CREATE TABLE ops_session (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  ops_id         BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  access_token   VARCHAR(512)    NOT NULL COMMENT '访问令牌(随机串/JWT均够用)',
  expire_at      DATETIME(3)     NOT NULL COMMENT '过期时间',
  client_ip      VARCHAR(45)     NULL COMMENT '客户端IP',
  user_agent     VARCHAR(512)    NULL COMMENT 'UA（浏览器/客户端环境可能较长）',
  session_status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 0失效1有效',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_token_active (access_token, is_deleted),
  KEY idx_ops_expire (ops_id, expire_at),
  CONSTRAINT chk_os_status CHECK (session_status IN (0, 1)),
  CONSTRAINT chk_os_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_os_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营后台会话表';


-- =============================================================================
-- 四、媒体与核心业务
-- =============================================================================

CREATE TABLE media_file (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文件ID',
  user_id          BIGINT UNSIGNED NULL COMMENT 'C端上传用户可空',
  ops_id           BIGINT UNSIGNED NULL COMMENT '运营上传可空',
  biz_type         TINYINT UNSIGNED NOT NULL COMMENT '业务类型1头像2血糖3餐食4聊天图5CMS6聊天语音',
  biz_id           VARCHAR(64)     NULL COMMENT '业务主键(如资讯ID)，便于素材复用与清理',
  storage_provider TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '存储1本地2COS3OSS',
  bucket_name      VARCHAR(128)    NULL COMMENT '桶名',
  storage_key      VARCHAR(512)    NOT NULL COMMENT '对象Key',
  file_url         VARCHAR(1024)   NOT NULL COMMENT '访问URL',
  mime_type        VARCHAR(128)    NULL COMMENT 'MIME',
  size_bytes       BIGINT UNSIGNED NULL COMMENT '字节数',
  width_px         INT UNSIGNED    NULL COMMENT '宽',
  height_px        INT UNSIGNED    NULL COMMENT '高',
  sha256_hex       CHAR(64)        NULL COMMENT '内容哈希(用于上传去重查询)',
  file_status      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态0禁用1可用2待清理',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_user_biz (user_id, biz_type),
  KEY idx_biz_ref (biz_type, biz_id),
  KEY idx_sha (sha256_hex),
  CONSTRAINT chk_mf_biz CHECK (biz_type IN (1, 2, 3, 4, 5, 6)),
  CONSTRAINT chk_mf_prov CHECK (storage_provider IN (1, 2, 3)),
  CONSTRAINT chk_mf_status CHECK (file_status IN (0, 1, 2)),
  CONSTRAINT chk_mf_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mf_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='媒体文件元数据表';

CREATE TABLE glucose_record (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '血糖记录ID',
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  record_date    DATE            NOT NULL COMMENT '业务日期',
  node_code      VARCHAR(32)     NOT NULL COMMENT '节点编码(须非餐点，应用层校验)',
  glucose_value  DECIMAL(4,1)    NOT NULL COMMENT '血糖mmol/L',
  source_type    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '来源1手动2OCR3设备',
  media_id       BIGINT UNSIGNED NULL COMMENT '照片ID',
  remark         VARCHAR(256)    NULL COMMENT '备注',
  recorded_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_date_node (user_id, record_date, node_code, is_deleted),
  KEY idx_user_date (user_id, record_date),
  CONSTRAINT chk_gr_val CHECK (glucose_value > 0 AND glucose_value < 40),
  CONSTRAINT chk_gr_src CHECK (source_type IN (1, 2, 3)),
  CONSTRAINT chk_gr_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_gr_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='血糖记录表';

CREATE TABLE meal_record (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '饮食记录ID',
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  record_date      DATE            NOT NULL COMMENT '业务日期',
  node_code        VARCHAR(32)     NOT NULL COMMENT '餐点节点(须餐点，应用层校验)',
  media_id         BIGINT UNSIGNED NULL COMMENT '餐食照片ID',
  foods_text       VARCHAR(512)    NULL COMMENT '食物摘要',
  carbs_g          DECIMAL(8,1)    NULL COMMENT '碳水克',
  hunger_level     TINYINT UNSIGNED NULL COMMENT '饥饱1饱腹2一般3微饿',
  supplement_text  VARCHAR(512)    NULL COMMENT '补充信息',
  predict_now      DECIMAL(4,1)    NULL COMMENT '当前预估血糖',
  predict_suggest  DECIMAL(4,1)    NULL COMMENT '建议预估血糖',
  suggest_text     VARCHAR(512)    NULL COMMENT '建议文案',
  recorded_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_date_node (user_id, record_date, node_code, is_deleted),
  KEY idx_user_date (user_id, record_date),
  CONSTRAINT chk_mr_hunger CHECK (hunger_level IS NULL OR hunger_level IN (1, 2, 3)),
  CONSTRAINT chk_mr_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mr_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='饮食记录表';

CREATE TABLE meal_food_item (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  meal_id        BIGINT UNSIGNED NOT NULL COMMENT '饮食记录ID',
  food_id        BIGINT UNSIGNED NULL COMMENT '营养库ID可空',
  food_name      VARCHAR(64)     NOT NULL COMMENT '食物名称',
  weight_g       DECIMAL(8,1)    NULL COMMENT '重量克',
  carbs_g        DECIMAL(8,1)    NULL COMMENT '碳水克',
  calories_kcal  DECIMAL(8,1)    NULL COMMENT '热量kcal',
  sort_no        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_meal (meal_id, sort_no),
  CONSTRAINT chk_mfi_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mfi_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='饮食明细表';

CREATE TABLE chat_session (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  user_id             BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  title               VARCHAR(128)    NULL COMMENT '标题',
  session_status      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态0归档1进行中2已结束',
  session_type        TINYINT UNSIGNED NOT NULL DEFAULT 3 COMMENT '类型1纯文字2纯语音3混合',
  model_code          VARCHAR(64)     NULL COMMENT '默认模型编码',
  system_prompt_ver   VARCHAR(32)     NULL COMMENT '系统提示词版本',
  context_summary     MEDIUMTEXT      NULL COMMENT '会话滚动摘要(超长对话压缩后供模型续用)',
  summary_upto_msg_id BIGINT UNSIGNED NULL COMMENT '摘要已覆盖至的消息ID',
  window_msg_count    SMALLINT UNSIGNED NOT NULL DEFAULT 20 COMMENT '送入模型的最近原文消息条数',
  msg_count           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '消息条数冗余',
  last_msg_at         DATETIME(3)     NULL COMMENT '最近消息时间',
  is_pinned           TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否置顶0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_user_last (user_id, last_msg_at),
  KEY idx_user_status (user_id, session_status),
  KEY idx_user_pin (user_id, is_pinned, last_msg_at),
  CONSTRAINT chk_cs_status CHECK (session_status IN (0, 1, 2)),
  CONSTRAINT chk_cs_type CHECK (session_type IN (1, 2, 3)),
  CONSTRAINT chk_cs_window CHECK (window_msg_count BETWEEN 1 AND 200),
  CONSTRAINT chk_cs_pin CHECK (is_pinned IN (0, 1)),
  CONSTRAINT chk_cs_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cs_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='聊天会话表(含滚动摘要与模型窗口参数)';

CREATE TABLE chat_message (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  session_id         BIGINT UNSIGNED NOT NULL COMMENT '会话ID',
  user_id            BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  seq_no             INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '会话内顺序号',
  msg_role           TINYINT UNSIGNED NOT NULL COMMENT '角色1用户2助手3系统',
  msg_type           TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '类型1文本2图片3语音(payload由应用层保证)',
  content_text       TEXT            NULL COMMENT '文本内容',
  media_id           BIGINT UNSIGNED NULL COMMENT '图片文件ID',
  voice_media_id     BIGINT UNSIGNED NULL COMMENT '语音文件ID',
  asr_text           VARCHAR(512)    NULL COMMENT '语音识别文本',
  voice_duration_ms  INT UNSIGNED    NULL COMMENT '语音时长毫秒',
  reply_to_msg_id    BIGINT UNSIGNED NULL COMMENT '回复的用户/引用消息ID',
  model_code         VARCHAR(64)     NULL COMMENT '本条助手回复所用模型',
  prompt_tokens      INT UNSIGNED    NULL COMMENT '提示token数(助手消息)',
  completion_tokens  INT UNSIGNED    NULL COMMENT '生成token数(助手消息)',
  finish_reason      TINYINT UNSIGNED NULL COMMENT '结束原因1正常2长度截断3内容过滤4错误',
  latency_ms         INT UNSIGNED    NULL COMMENT '模型耗时毫秒',
  client_msg_id      VARCHAR(64)     NULL COMMENT '客户端幂等ID；应用层捕获MySQL1062当成功',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_session_client_active (session_id, client_msg_id, is_deleted),
  KEY idx_session_seq (session_id, seq_no),
  KEY idx_session_time (session_id, gmt_create),
  KEY idx_reply_to (reply_to_msg_id),
  CONSTRAINT chk_cm_role CHECK (msg_role IN (1, 2, 3)),
  CONSTRAINT chk_cm_type CHECK (msg_type IN (1, 2, 3)),
  CONSTRAINT chk_cm_finish CHECK (finish_reason IS NULL OR finish_reason IN (1, 2, 3, 4)),
  CONSTRAINT chk_cm_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cm_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='聊天消息表(含语音与token计量；payload完整性由应用层保证)';

CREATE TABLE chat_context (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '上下文ID',
  session_id       BIGINT UNSIGNED NOT NULL COMMENT '会话ID',
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  trigger_msg_id   BIGINT UNSIGNED NULL COMMENT '触发本轮的用户消息ID',
  assistant_msg_id BIGINT UNSIGNED NULL COMMENT '本轮生成的助手消息ID(回写)',
  context_round    INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '会话内轮次号从1起',
  context_type     TINYINT UNSIGNED NOT NULL COMMENT '类型1系统提示2健康档案快照3历史摘要4业务数据5工具结果6其他',
  context_key      VARCHAR(64)     NULL COMMENT '键如system_prompt/health_profile/glucose_recent',
  content_text     MEDIUMTEXT      NULL COMMENT '文本上下文',
  content_json     JSON            NULL COMMENT '结构化上下文(亦可存整包窗口JSON)',
  token_estimate   INT UNSIGNED    NULL COMMENT '本分片预估token',
  model_code       VARCHAR(64)     NULL COMMENT '本轮调用模型编码',
  sort_no          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '同分片组内排序',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_session_round (session_id, context_round, sort_no),
  KEY idx_trigger_msg (trigger_msg_id),
  KEY idx_assistant_msg (assistant_msg_id),
  KEY idx_user_time (user_id, gmt_create),
  CONSTRAINT chk_cc_type CHECK (context_type IN (1, 2, 3, 4, 5, 6)),
  CONSTRAINT chk_cc_payload CHECK (content_text IS NOT NULL OR content_json IS NOT NULL),
  CONSTRAINT chk_cc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI对话上下文表(窗口切片+分片审计；替代chat_context_snapshot)';

CREATE TABLE chat_user_memory (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记忆ID',
  user_id           BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  memory_key        VARCHAR(64)     NOT NULL COMMENT '记忆键如diet_taboo',
  memory_text       VARCHAR(512)    NOT NULL COMMENT '记忆内容',
  source_session_id BIGINT UNSIGNED NULL COMMENT '来源会话ID可空',
  confidence        TINYINT UNSIGNED NOT NULL DEFAULT 100 COMMENT '置信度0-100',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_key_active (user_id, memory_key, is_deleted),
  KEY idx_user (user_id),
  CONSTRAINT chk_cum_conf CHECK (confidence <= 100),
  CONSTRAINT chk_cum_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cum_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI跨会话长期记忆表';

CREATE TABLE feedback (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  feedback_type   TINYINT UNSIGNED NOT NULL COMMENT '类型1建议2Bug3诉求4其他',
  content_text    VARCHAR(1000)   NOT NULL COMMENT '留言内容',
  reply_status    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态0已收到1处理中2已处理3已关闭',
  official_reply  VARCHAR(1000)   NULL COMMENT '官方回复空则前端不展示',
  replied_by      BIGINT UNSIGNED NULL COMMENT '回复人运营账号ID',
  replied_at      DATETIME(3)     NULL COMMENT '回复时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_user_time (user_id, gmt_create),
  KEY idx_status_time (reply_status, gmt_create),
  CONSTRAINT chk_fb_type CHECK (feedback_type IN (1, 2, 3, 4)),
  CONSTRAINT chk_fb_status CHECK (reply_status IN (0, 1, 2, 3)),
  CONSTRAINT chk_fb_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fb_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户反馈与官方回复表';

CREATE TABLE ocr_task (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  media_id        BIGINT UNSIGNED NOT NULL COMMENT '源图片ID',
  biz_type        TINYINT UNSIGNED NOT NULL COMMENT '业务1血糖2餐食',
  task_status     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待处理1进行中2成功3失败',
  ocr_result_json LONGTEXT        NULL COMMENT '识别结果JSON',
  error_msg       VARCHAR(512)    NULL COMMENT '失败原因',
  finished_at     DATETIME(3)     NULL COMMENT '完成时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_user_status (user_id, task_status),
  KEY idx_media (media_id),
  CONSTRAINT chk_ot_biz CHECK (biz_type IN (1, 2)),
  CONSTRAINT chk_ot_st CHECK (task_status IN (0, 1, 2, 3)),
  CONSTRAINT chk_ot_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_ot_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='OCR识别任务表';


-- =============================================================================
-- 五、内容与工具
-- =============================================================================

CREATE TABLE food_category (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  category_name VARCHAR(32)     NOT NULL COMMENT '分类名：主食类/肉蛋类等',
  sort_no       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序',
  is_enabled    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_name_active (category_name, is_deleted),
  KEY idx_sort (sort_no),
  CONSTRAINT chk_fc_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_fc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='食物分类表';

CREATE TABLE food_nutrition (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '食物ID',
  food_name           VARCHAR(64)     NOT NULL COMMENT '食物名称(应用层须trim规范化)',
  category_id         BIGINT UNSIGNED NULL COMMENT '分类ID(food_category.id)，后台维护主用',
  category_code       TINYINT UNSIGNED NOT NULL COMMENT '大类枚举1-9(兼容，与category_id并存)',
  carbs_per_100g      DECIMAL(8,1)    NOT NULL DEFAULT 0 COMMENT '每100g碳水g',
  calories_per_100g   DECIMAL(8,1)    NOT NULL DEFAULT 0 COMMENT '每100g热量kcal',
  gi_value            DECIMAL(5,1)    NULL COMMENT 'GI值可空',
  image_url           VARCHAR(512)    NULL COMMENT '缩略图',
  shelf_status        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '上架0下架1上架',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_cat_name (category_code, food_name, is_deleted),
  KEY idx_cat_shelf (category_code, shelf_status),
  KEY idx_category_shelf (category_id, shelf_status),
  CONSTRAINT chk_fn_cat CHECK (category_code BETWEEN 1 AND 9),
  CONSTRAINT chk_fn_shelf CHECK (shelf_status IN (0, 1)),
  CONSTRAINT chk_fn_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fn_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='食物营养库表';

CREATE TABLE cms_banner (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'BannerID',
  title         VARCHAR(64)     NOT NULL COMMENT '标题',
  subtitle      VARCHAR(128)    NULL COMMENT '副标题',
  image_url     VARCHAR(512)    NULL COMMENT '图片URL',
  link_url      VARCHAR(512)    NULL COMMENT '跳转URL',
  sort_no       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序升序',
  shelf_status  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0停用1启用',
  start_at      DATETIME(3)     NULL COMMENT '生效开始',
  end_at        DATETIME(3)     NULL COMMENT '生效结束',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_shelf_sort (shelf_status, sort_no),
  CONSTRAINT chk_cb_shelf CHECK (shelf_status IN (0, 1)),
  CONSTRAINT chk_cb_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cb_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='首页Banner表';

CREATE TABLE cms_news (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资讯ID',
  title          VARCHAR(128)    NOT NULL COMMENT '标题',
  summary_text   VARCHAR(512)    NULL COMMENT '摘要',
  channel_code   TINYINT UNSIGNED NOT NULL COMMENT '频道1企业动态2行业快讯3设备新知4控糖科普',
  content_type   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '类型1图文2视频',
  cover_url      VARCHAR(512)    NULL COMMENT '封面',
  video_url      VARCHAR(512)    NULL COMMENT '视频地址',
  content_body   MEDIUMTEXT      NULL COMMENT '正文',
  publish_at     DATETIME(3)     NOT NULL COMMENT '发布时间',
  publish_status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0草稿1已发布2下架',
  like_count     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞冗余计数',
  collect_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '收藏冗余计数',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_ch_pub (channel_code, publish_at),
  KEY idx_st_pub (publish_status, publish_at),
  KEY idx_ch_st_pub (channel_code, publish_status, publish_at),
  CONSTRAINT chk_cn_ch CHECK (channel_code IN (1, 2, 3, 4)),
  CONSTRAINT chk_cn_type CHECK (content_type IN (1, 2)),
  CONSTRAINT chk_cn_st CHECK (publish_status IN (0, 1, 2)),
  CONSTRAINT chk_cn_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cn_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='资讯内容表';

CREATE TABLE news_user_action (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  news_id       BIGINT UNSIGNED NOT NULL COMMENT '资讯ID',
  is_liked      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否点赞0否1是',
  is_collected  TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否收藏0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_user_news_active (user_id, news_id, is_deleted),
  CONSTRAINT chk_nua_flags CHECK (is_liked IN (0, 1) AND is_collected IN (0, 1)),
  CONSTRAINT chk_nua_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_nua_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='资讯点赞收藏表';

CREATE TABLE data_export_task (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  export_scope  TINYINT UNSIGNED NOT NULL COMMENT '导出范围1近7天2近30天3自定义',
  start_date    DATE            NULL COMMENT '开始日期',
  end_date      DATE            NULL COMMENT '结束日期',
  file_url      VARCHAR(1024)   NULL COMMENT '结果文件',
  summary_text  MEDIUMTEXT      NULL COMMENT '文本摘要',
  task_status   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待处理1进行中2成功3失败',
  error_msg     VARCHAR(512)    NULL COMMENT '失败原因',
  finished_at   DATETIME(3)     NULL COMMENT '完成时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_user_time (user_id, gmt_create),
  KEY idx_status_gmt (task_status, gmt_create),
  CONSTRAINT chk_det_scope CHECK (export_scope IN (1, 2, 3)),
  CONSTRAINT chk_det_st CHECK (task_status IN (0, 1, 2, 3)),
  CONSTRAINT chk_det_custom CHECK (
    export_scope <> 3 OR (start_date IS NOT NULL AND end_date IS NOT NULL AND start_date <= end_date)
  ),
  CONSTRAINT chk_det_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_det_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='数据导出任务表';

CREATE TABLE data_backup (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '备份ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  backup_version  INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '格式版本',
  storage_type    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1内嵌JSON2对象存储',
  payload_json    LONGTEXT        NULL COMMENT '内嵌JSON',
  object_url      VARCHAR(1024)   NULL COMMENT '对象URL',
  size_bytes      BIGINT UNSIGNED NULL COMMENT '大小',
  checksum_sha256 CHAR(64)        NULL COMMENT '校验和',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_user_time (user_id, gmt_create),
  CONSTRAINT chk_db_st CHECK (storage_type IN (1, 2)),
  CONSTRAINT chk_db_payload CHECK (
    (storage_type = 1 AND payload_json IS NOT NULL)
    OR (storage_type = 2 AND object_url IS NOT NULL)
  ),
  CONSTRAINT chk_db_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_db_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户数据备份表';


-- =============================================================================
-- 六、AI 与推送
-- =============================================================================

CREATE TABLE sys_config (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  config_key     VARCHAR(64)     NOT NULL COMMENT '参数键如glucose.target.low',
  config_value   VARCHAR(2000)   NULL COMMENT '参数值JSON或标量',
  value_type     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1字符串2数字3布尔4JSON',
  config_group   VARCHAR(32)     NOT NULL DEFAULT 'COMMON' COMMENT '分组GLUCOSE/APPEARANCE/NOTIFY/TEXT/PUSH/AI',
  remark         VARCHAR(255)    NULL COMMENT '说明',
  modifier_name  VARCHAR(64)     NULL COMMENT '最后修改人账号',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_config_key_active (config_key, is_deleted),
  KEY idx_group (config_group),
  CONSTRAINT chk_sc_vtype CHECK (value_type IN (1, 2, 3, 4)),
  CONSTRAINT chk_sc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_sc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='系统参数表';

CREATE TABLE ai_model_config (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  model_code      VARCHAR(64)     NOT NULL COMMENT '模型编码唯一',
  model_name      VARCHAR(64)     NOT NULL COMMENT '展示名称',
  provider_code   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '厂商1自研2OpenAI兼容3通义4文心5其他',
  api_base_url    VARCHAR(512)    NULL COMMENT 'API Base URL',
  api_key_cipher  VARCHAR(512)    NULL COMMENT 'API Key密文或KMS引用，禁止明文',
  model_endpoint  VARCHAR(128)    NULL COMMENT '模型名/部署名',
  temperature     DECIMAL(3,2)    NOT NULL DEFAULT 0.70 COMMENT '温度',
  max_tokens      INT UNSIGNED    NOT NULL DEFAULT 2048 COMMENT '最大输出token',
  timeout_ms      INT UNSIGNED    NOT NULL DEFAULT 30000 COMMENT '超时毫秒',
  is_default      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否默认模型0否1是',
  is_enabled      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用0否1是',
  remark          VARCHAR(255)    NULL COMMENT '备注',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_model_code_active (model_code, is_deleted),
  KEY idx_enabled (is_enabled, is_default),
  CONSTRAINT chk_amc_prov CHECK (provider_code BETWEEN 1 AND 5),
  CONSTRAINT chk_amc_def CHECK (is_default IN (0, 1)),
  CONSTRAINT chk_amc_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_amc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_amc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI模型配置表';

CREATE TABLE ai_prompt_template (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  scene_code      VARCHAR(64)     NOT NULL COMMENT '场景chat_system/meal_analysis/glucose_advice',
  template_name   VARCHAR(64)     NOT NULL COMMENT '模板名称',
  prompt_body     TEXT            NOT NULL COMMENT '提示词正文',
  model_code      VARCHAR(64)     NULL COMMENT '绑定模型编码可空则用默认',
  version_no      INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '版本号',
  is_enabled      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_scene_ver_active (scene_code, version_no, is_deleted),
  KEY idx_scene_en (scene_code, is_enabled),
  CONSTRAINT chk_apt_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_apt_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_apt_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI提示词模板表';

CREATE TABLE msg_push_template (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  template_code   VARCHAR(64)     NOT NULL COMMENT '模板编码',
  template_name   VARCHAR(64)     NOT NULL COMMENT '模板名称',
  channel_type    TINYINT UNSIGNED NOT NULL COMMENT '渠道1微信订阅消息2短信3站内',
  third_tpl_id    VARCHAR(128)    NULL COMMENT '第三方模板ID',
  title           VARCHAR(128)    NULL COMMENT '标题',
  content_body    VARCHAR(1000)   NOT NULL COMMENT '内容模板含占位符',
  is_enabled      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_code_active (template_code, is_deleted),
  CONSTRAINT chk_mpt_ch CHECK (channel_type IN (1, 2, 3)),
  CONSTRAINT chk_mpt_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_mpt_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mpt_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='消息推送模板表';

CREATE TABLE msg_push_task (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_name       VARCHAR(128)    NOT NULL COMMENT '任务名称',
  template_id     BIGINT UNSIGNED NOT NULL COMMENT '模板ID',
  channel_type    TINYINT UNSIGNED NOT NULL COMMENT '渠道1微信2短信3站内',
  target_type     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '目标1全部用户2按标签3指定用户列表',
  target_json     VARCHAR(2000)   NULL COMMENT '目标参数JSON',
  payload_json    VARCHAR(2000)   NULL COMMENT '填充变量JSON',
  schedule_at     DATETIME(3)     NULL COMMENT '计划发送时间空则立即',
  task_status     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0草稿1待发送2发送中3成功4部分失败5取消',
  success_count   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '成功数',
  fail_count      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '失败数',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_status_sched (task_status, schedule_at),
  KEY idx_tpl (template_id),
  CONSTRAINT chk_mptask_ch CHECK (channel_type IN (1, 2, 3)),
  CONSTRAINT chk_mptask_tg CHECK (target_type IN (1, 2, 3)),
  CONSTRAINT chk_mptask_st CHECK (task_status IN (0, 1, 2, 3, 4, 5)),
  CONSTRAINT chk_mptask_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mptask_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='消息推送任务表';

CREATE TABLE msg_push_record (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_id         BIGINT UNSIGNED NOT NULL COMMENT '任务ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  channel_type    TINYINT UNSIGNED NOT NULL COMMENT '渠道',
  send_status     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待发1成功2失败',
  error_msg       VARCHAR(512)    NULL COMMENT '失败原因',
  sent_at         DATETIME(3)     NULL COMMENT '发送时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_task_user (task_id, user_id),
  KEY idx_user_time (user_id, gmt_create),
  CONSTRAINT chk_mpr_ch CHECK (channel_type IN (1, 2, 3)),
  CONSTRAINT chk_mpr_st CHECK (send_status IN (0, 1, 2)),
  CONSTRAINT chk_mpr_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mpr_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='消息推送明细表';


-- =============================================================================
-- 七、审计（只追加）
-- =============================================================================

CREATE TABLE ops_audit_log (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审计ID',
  ops_id           BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  operator_name    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '操作人账号冗余',
  role_code        VARCHAR(64)     NULL COMMENT '操作时角色编码',
  module_code      VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '模块dashboard/news/banner/media/food/feedback/export/user/archive/system/push/ai',
  action_code      VARCHAR(64)     NOT NULL COMMENT '动作编码如feedback_reply',
  action_name      VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '动作中文',
  target_type      VARCHAR(32)     NOT NULL COMMENT '目标类型如feedback',
  target_id        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '目标主键0表示无',
  detail_json      VARCHAR(2000)   NULL COMMENT '详情JSON',
  request_uri      VARCHAR(255)    NULL COMMENT '请求URI',
  request_method   VARCHAR(10)     NULL COMMENT '请求方法',
  client_ip        VARCHAR(45)     NULL COMMENT 'IP',
  cost_ms          INT UNSIGNED    NULL COMMENT '耗时ms',
  result_code      INT             NULL COMMENT '业务返回码',
  created_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人(通常等于ops_id)',
  gmt_create       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_ops_time (ops_id, gmt_create),
  KEY idx_module_action (module_code, action_code),
  KEY idx_target (target_type, target_id),
  KEY idx_gmt_create (gmt_create)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营操作审计表(只追加，无gmt_modified)';

SET FOREIGN_KEY_CHECKS = 1;


-- =============================================================================
-- 种子数据
-- =============================================================================

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


-- =============================================================================
-- 应用层需保证的约束（本 DDL 无外键/无触发器）
-- =============================================================================
-- 1. glucose_record.node_code 必须对应 dict_glucose_node.is_meal=0 且有效
-- 2. meal_record.node_code 必须对应 dict_glucose_node.is_meal=1 且有效
-- 3. user_id / ops_id / session_id / media_id / meal_id / news_id / template_id 等关联存在性由应用层校验
-- 4. 更新业务行时 gmt_modified 由 ON UPDATE 自动刷新；显式 UPDATE 勿误覆盖为旧值
-- 5. chat_message.client_msg_id 幂等：捕获 MySQL 1062(Duplicate) 当成功，勿抛给用户
-- 6. chat_message payload：文本/图片/语音及混合、流式占位消息由应用层校验，不在库层 CHECK
-- 7. 逻辑删除唯一索引策略：同一业务键在 is_deleted=0 仅一条；再次注销前需硬删或改键，避免 is_deleted=1 冲突
-- =============================================================================
