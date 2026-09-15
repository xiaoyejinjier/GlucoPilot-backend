-- =============================================================================
-- AI 控糖助手 · 生产库 DDL（C 端 + 后台管理端 · 全量合并版）
-- 版本：v2026-09-14-full-merge-r1
-- 主基线：docs/智能健康管理助手数据库设计-wcy版.sql
-- 合并来源：docs/docs/sql/admin_schema.sql（独有表/字段/种子，按 wcy 规范改写）
--
-- 合并说明（以 wcy 为主）：
-- 1. 保留 wcy：审计七件套、逻辑删除 VIRTUAL 唯一、物理外键、CHECK、ops_* 账号体系
-- 2. 不采用 admin：gmt_create/gmt_modified、sys_admin、禁用物理外键等阿里手册口径
-- 3. 自 admin 并入：food_category、sys_config；ops_audit_log 请求上下文增强；
--    media_file.biz_id、food_nutrition.category_id/gi_value、后台查询索引、权限/配置种子
--
-- 规范：
-- 1. 表名/字段名：小写 + 下划线，见名知意
-- 2. 审计七件套：created_by/created_at/updated_by/updated_at/deleted_by/deleted_at/is_deleted
--    （ops_audit_log 例外：只追加，仅保留 created_by/created_at）
-- 3. 逻辑删除与唯一索引：用 VIRTUAL 生成列（active_flag / xxx_active）配合 UNIQUE，避免注销后无法复用
-- 4. is_deleted 与 deleted_at 一致性由 CHECK 约束保证
-- 5. 登录与资料分表；能整型不用字符型；表与字段均有 COMMENT
-- 6. MySQL 8.0.16+（CHECK）
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS sugar_health
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE sugar_health;

-- ---------- 逆序删除（可重复执行）----------
DROP TABLE IF EXISTS ops_audit_log;
DROP TABLE IF EXISTS meal_food_item;
DROP TABLE IF EXISTS news_user_action;
DROP TABLE IF EXISTS chat_context;
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
DROP TABLE IF EXISTS sys_config;
DROP TABLE IF EXISTS user_setting;
DROP TABLE IF EXISTS user_health;
DROP TABLE IF EXISTS user_profile;
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

-- -----------------------------------------------------------------------------
-- 表：dict_glucose_node
-- 用途：九时段节点字典（血糖非餐点 / 饮食餐点）。
-- -----------------------------------------------------------------------------
CREATE TABLE dict_glucose_node (
  node_code     VARCHAR(32)     NOT NULL COMMENT '节点编码',
  node_name     VARCHAR(32)     NOT NULL COMMENT '节点名称',
  short_name    VARCHAR(16)     NOT NULL COMMENT '短名',
  is_meal       TINYINT         NOT NULL DEFAULT 0 COMMENT '是否餐点 0否1是',
  sort_no       INT             NOT NULL DEFAULT 0 COMMENT '排序',
  is_enabled    TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用 0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (node_code),
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
-- 二、C 端用户（登录 / 资料 / 健康 / 设置 分表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 表：user_account
-- 用途：C 端登录身份（微信 openid、手机号、账号状态）。不含展示资料。
-- -----------------------------------------------------------------------------
CREATE TABLE user_account (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户账号ID',
  openid          VARCHAR(64)     NOT NULL COMMENT '微信 openid',
  union_id        VARCHAR(64)     NULL COMMENT '微信 unionid',
  phone           VARCHAR(20)     NULL COMMENT '绑定手机号',
  account_status  TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0禁用1正常2注销中',
  last_login_at   DATETIME(3)     NULL COMMENT '最近登录时间',
  last_login_ip   VARCHAR(45)     NULL COMMENT '最近登录IP',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  openid_active   VARCHAR(64) GENERATED ALWAYS AS (IF(is_deleted = 0, openid, NULL)) VIRTUAL COMMENT '未删除时openid',
  union_id_active VARCHAR(64) GENERATED ALWAYS AS (IF(is_deleted = 0, union_id, NULL)) VIRTUAL COMMENT '未删除时unionid',
  phone_active    VARCHAR(20) GENERATED ALWAYS AS (IF(is_deleted = 0, phone, NULL)) VIRTUAL COMMENT '未删除时手机号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_openid_active (openid_active),
  UNIQUE KEY uk_union_id_active (union_id_active),
  UNIQUE KEY uk_phone_active (phone_active),
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

-- -----------------------------------------------------------------------------
-- 表：user_profile
-- 用途：C 端基本信息（昵称、头像等展示字段）。
-- -----------------------------------------------------------------------------
CREATE TABLE user_profile (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资料ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  nickname      VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '昵称',
  avatar_url    VARCHAR(512)    NULL COMMENT '头像URL',
  avatar_text   VARCHAR(8)      NULL COMMENT '默认头像文字',
  gender        TINYINT         NOT NULL DEFAULT 0 COMMENT '性别 0未知1男2女',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag   TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_active (user_id, active_flag),
  CONSTRAINT fk_up_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT chk_up_gender CHECK (gender IN (0, 1, 2)),
  CONSTRAINT chk_up_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_up_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户基本信息表';

-- -----------------------------------------------------------------------------
-- 表：user_health
-- 用途：健康档案（病型、用药、运动、目标血糖）。
-- -----------------------------------------------------------------------------
CREATE TABLE user_health (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '健康档案ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  diabetes_type        TINYINT         NOT NULL DEFAULT 0 COMMENT '糖尿病类型 0未填1一型2二型3妊娠4其他',
  diabetes_type_text   VARCHAR(32)     NULL COMMENT '类型补充文案',
  diagnosed_ym         CHAR(7)         NULL COMMENT '确诊年月YYYY-MM',
  age                  SMALLINT UNSIGNED NULL COMMENT '年龄',
  medication           VARCHAR(256)    NULL COMMENT '用药说明',
  exercise_habit       VARCHAR(256)    NULL COMMENT '运动习惯',
  target_fasting       DECIMAL(4,1)    NULL COMMENT '空腹目标mmol/L',
  target_postprandial  DECIMAL(4,1)    NULL COMMENT '餐后目标mmol/L',
  created_by           BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by           BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by           BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at           DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag          TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_active (user_id, active_flag),
  CONSTRAINT fk_uh_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT chk_uh_dtype CHECK (diabetes_type IN (0, 1, 2, 3, 4)),
  CONSTRAINT chk_uh_age CHECK (age IS NULL OR age <= 150),
  CONSTRAINT chk_uh_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_uh_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C端用户健康档案表';

-- -----------------------------------------------------------------------------
-- 表：user_setting
-- 用途：展示与通知偏好、免责声明同意。
-- -----------------------------------------------------------------------------
CREATE TABLE user_setting (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '设置ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  font_size            TINYINT         NOT NULL DEFAULT 2 COMMENT '字号 1标准2较大3更大',
  theme_code           TINYINT         NOT NULL DEFAULT 1 COMMENT '主题 1绿2青绿3简洁',
  notify_glucose       TINYINT         NOT NULL DEFAULT 1 COMMENT '血糖提醒 0关1开',
  notify_meal          TINYINT         NOT NULL DEFAULT 1 COMMENT '饮食提醒 0关1开',
  notify_news          TINYINT         NOT NULL DEFAULT 0 COMMENT '资讯提醒 0关1开',
  disclaimer_agreed    TINYINT         NOT NULL DEFAULT 0 COMMENT '免责声明 0未同意1已同意',
  disclaimer_version   VARCHAR(32)     NULL COMMENT '免责声明版本号',
  disclaimer_agreed_at DATETIME(3)     NULL COMMENT '同意时间',
  created_by           BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by           BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by           BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at           DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag          TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_active (user_id, active_flag),
  CONSTRAINT fk_us_user FOREIGN KEY (user_id) REFERENCES user_account (id),
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

-- =============================================================================
-- 三、运营账号 + RBAC + 会话
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 表：ops_account
-- 用途：后台运营登录账号（用户名、密码哈希、状态）。
-- -----------------------------------------------------------------------------
CREATE TABLE ops_account (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运营账号ID',
  username        VARCHAR(64)     NOT NULL COMMENT '登录名',
  password_hash   VARCHAR(255)    NOT NULL COMMENT '密码哈希(bcrypt/argon2)',
  account_status  TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0禁用1正常',
  last_login_at   DATETIME(3)     NULL COMMENT '最近登录时间',
  last_login_ip   VARCHAR(45)     NULL COMMENT '最近登录IP',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  username_active VARCHAR(64) GENERATED ALWAYS AS (IF(is_deleted = 0, username, NULL)) VIRTUAL COMMENT '未删除时登录名',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username_active (username_active),
  CONSTRAINT chk_oa_status CHECK (account_status IN (0, 1)),
  CONSTRAINT chk_oa_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_oa_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营后台登录账号表';

-- -----------------------------------------------------------------------------
-- 表：ops_profile
-- 用途：运营人员基本信息（与登录凭证分离）。
-- -----------------------------------------------------------------------------
CREATE TABLE ops_profile (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运营资料ID',
  ops_id        BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  real_name     VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '真实姓名',
  mobile        VARCHAR(20)     NULL COMMENT '手机号',
  email         VARCHAR(128)    NULL COMMENT '邮箱',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag   TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ops_active (ops_id, active_flag),
  CONSTRAINT fk_op_ops FOREIGN KEY (ops_id) REFERENCES ops_account (id),
  CONSTRAINT chk_op_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_op_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营人员基本信息表';

-- -----------------------------------------------------------------------------
-- 表：sys_role
-- 用途：后台角色（超管、运营、内容编辑等）。
-- -----------------------------------------------------------------------------
CREATE TABLE sys_role (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  role_code     VARCHAR(64)     NOT NULL COMMENT '角色编码',
  role_name     VARCHAR(64)     NOT NULL COMMENT '角色名称',
  sort_no       INT             NOT NULL DEFAULT 0 COMMENT '排序',
  role_status   TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0停用1启用',
  remark        VARCHAR(256)    NULL COMMENT '备注',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  role_code_active VARCHAR(64) GENERATED ALWAYS AS (IF(is_deleted = 0, role_code, NULL)) VIRTUAL COMMENT '未删除时角色编码',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code_active (role_code_active),
  CONSTRAINT chk_role_status CHECK (role_status IN (0, 1)),
  CONSTRAINT chk_role_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_role_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='系统角色表';

-- -----------------------------------------------------------------------------
-- 表：sys_permission
-- 用途：权限点（菜单/按钮/接口），如 feedback:reply。
-- -----------------------------------------------------------------------------
CREATE TABLE sys_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  perm_code     VARCHAR(128)    NOT NULL COMMENT '权限编码',
  perm_name     VARCHAR(64)     NOT NULL COMMENT '权限名称',
  perm_type     TINYINT         NOT NULL COMMENT '类型 1菜单2按钮3接口',
  parent_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父权限ID 0为根',
  path_url      VARCHAR(256)    NULL COMMENT '路由或接口路径',
  sort_no       INT             NOT NULL DEFAULT 0 COMMENT '排序',
  perm_status   TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0停用1启用',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  perm_code_active VARCHAR(128) GENERATED ALWAYS AS (IF(is_deleted = 0, perm_code, NULL)) VIRTUAL COMMENT '未删除时权限编码',
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code_active (perm_code_active),
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

-- -----------------------------------------------------------------------------
-- 表：sys_ops_role
-- 用途：运营账号与角色多对多。
-- -----------------------------------------------------------------------------
CREATE TABLE sys_ops_role (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  ops_id        BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  role_id       BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag   TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ops_role_active (ops_id, role_id, active_flag),
  KEY idx_role (role_id),
  CONSTRAINT fk_sor_ops FOREIGN KEY (ops_id) REFERENCES ops_account (id),
  CONSTRAINT fk_sor_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
  CONSTRAINT chk_sor_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_sor_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营账号角色关联表';

-- -----------------------------------------------------------------------------
-- 表：sys_role_permission
-- 用途：角色与权限多对多。
-- -----------------------------------------------------------------------------
CREATE TABLE sys_role_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  role_id       BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
  perm_id       BIGINT UNSIGNED NOT NULL COMMENT '权限ID',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag   TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm_active (role_id, perm_id, active_flag),
  KEY idx_perm (perm_id),
  CONSTRAINT fk_srp_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
  CONSTRAINT fk_srp_perm FOREIGN KEY (perm_id) REFERENCES sys_permission (id),
  CONSTRAINT chk_srp_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_srp_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='角色权限关联表';

-- -----------------------------------------------------------------------------
-- 表：ops_session
-- 用途：后台登录会话 / Token。
-- -----------------------------------------------------------------------------
CREATE TABLE ops_session (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  ops_id        BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  access_token  VARCHAR(512)    NOT NULL COMMENT '访问令牌(随机串/JWT均够用)',
  expire_at     DATETIME(3)     NOT NULL COMMENT '过期时间',
  client_ip     VARCHAR(45)     NULL COMMENT '客户端IP',
  user_agent    VARCHAR(256)    NULL COMMENT 'UA',
  session_status TINYINT        NOT NULL DEFAULT 1 COMMENT '状态 0失效1有效',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_token (access_token),
  KEY idx_ops_expire (ops_id, expire_at),
  CONSTRAINT fk_os_ops FOREIGN KEY (ops_id) REFERENCES ops_account (id),
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

-- -----------------------------------------------------------------------------
-- 表：media_file
-- 用途：媒体文件元数据（头像/血糖拍照/餐食/聊天/CMS）。
-- -----------------------------------------------------------------------------
CREATE TABLE media_file (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文件ID',
  user_id          BIGINT UNSIGNED NULL COMMENT 'C端上传用户可空',
  ops_id           BIGINT UNSIGNED NULL COMMENT '运营上传可空',
  biz_type         TINYINT         NOT NULL COMMENT '业务类型1头像2血糖3餐食4聊天5CMS',
  biz_id           VARCHAR(64)     NULL COMMENT '业务主键(如资讯ID)，便于素材复用与清理',
  storage_provider TINYINT         NOT NULL DEFAULT 1 COMMENT '存储1本地2COS3OSS',
  bucket_name      VARCHAR(128)    NULL COMMENT '桶名',
  storage_key      VARCHAR(512)    NOT NULL COMMENT '对象Key',
  file_url         VARCHAR(1024)   NOT NULL COMMENT '访问URL',
  mime_type        VARCHAR(128)    NULL COMMENT 'MIME',
  size_bytes       BIGINT UNSIGNED NULL COMMENT '字节数',
  width_px         INT UNSIGNED    NULL COMMENT '宽',
  height_px        INT UNSIGNED    NULL COMMENT '高',
  sha256_hex       CHAR(64)        NULL COMMENT '内容哈希(用于上传去重查询)',
  file_status      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态0禁用1可用2待清理',
  created_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by       BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at       DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_user_biz (user_id, biz_type),
  KEY idx_biz_ref (biz_type, biz_id),
  KEY idx_sha (sha256_hex),
  CONSTRAINT fk_mf_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_mf_ops FOREIGN KEY (ops_id) REFERENCES ops_account (id),
  CONSTRAINT chk_mf_biz CHECK (biz_type IN (1, 2, 3, 4, 5)),
  CONSTRAINT chk_mf_prov CHECK (storage_provider IN (1, 2, 3)),
  CONSTRAINT chk_mf_status CHECK (file_status IN (0, 1, 2)),
  CONSTRAINT chk_mf_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mf_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='媒体文件元数据表';

-- -----------------------------------------------------------------------------
-- 表：glucose_record
-- 用途：非餐点血糖记录；同一用户同日同节点仅一条有效数据。
-- -----------------------------------------------------------------------------
CREATE TABLE glucose_record (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '血糖记录ID',
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  record_date    DATE            NOT NULL COMMENT '业务日期',
  node_code      VARCHAR(32)     NOT NULL COMMENT '节点编码',
  glucose_value  DECIMAL(4,1)    NOT NULL COMMENT '血糖mmol/L',
  source_type    TINYINT         NOT NULL DEFAULT 1 COMMENT '来源1手动2OCR3设备',
  media_id       BIGINT UNSIGNED NULL COMMENT '照片ID',
  remark         VARCHAR(256)    NULL COMMENT '备注',
  recorded_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认时间',
  created_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by     BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at     DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag    TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_date_node (user_id, record_date, node_code, active_flag),
  KEY idx_user_date (user_id, record_date),
  CONSTRAINT fk_gr_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_gr_node FOREIGN KEY (node_code) REFERENCES dict_glucose_node (node_code),
  CONSTRAINT fk_gr_media FOREIGN KEY (media_id) REFERENCES media_file (id),
  CONSTRAINT chk_gr_val CHECK (glucose_value > 0 AND glucose_value < 40),
  CONSTRAINT chk_gr_src CHECK (source_type IN (1, 2, 3)),
  CONSTRAINT chk_gr_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_gr_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='血糖记录表';

-- -----------------------------------------------------------------------------
-- 表：meal_record
-- 用途：餐点饮食记录与两段式血糖预测。
-- -----------------------------------------------------------------------------
CREATE TABLE meal_record (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '饮食记录ID',
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  record_date      DATE            NOT NULL COMMENT '业务日期',
  node_code        VARCHAR(32)     NOT NULL COMMENT '餐点节点',
  media_id         BIGINT UNSIGNED NULL COMMENT '餐食照片ID',
  foods_text       VARCHAR(512)    NULL COMMENT '食物摘要',
  carbs_g          DECIMAL(8,1)    NULL COMMENT '碳水克',
  hunger_level     TINYINT         NULL COMMENT '饥饱1饱腹2一般3微饿',
  supplement_text  VARCHAR(512)    NULL COMMENT '补充信息',
  predict_now      DECIMAL(4,1)    NULL COMMENT '当前预估血糖',
  predict_suggest  DECIMAL(4,1)    NULL COMMENT '建议预估血糖',
  suggest_text     VARCHAR(512)    NULL COMMENT '建议文案',
  recorded_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认时间',
  created_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by       BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at       DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag      TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_date_node (user_id, record_date, node_code, active_flag),
  KEY idx_user_date (user_id, record_date),
  CONSTRAINT fk_mr_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_mr_node FOREIGN KEY (node_code) REFERENCES dict_glucose_node (node_code),
  CONSTRAINT fk_mr_media FOREIGN KEY (media_id) REFERENCES media_file (id),
  CONSTRAINT chk_mr_hunger CHECK (hunger_level IS NULL OR hunger_level IN (1, 2, 3)),
  CONSTRAINT chk_mr_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mr_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='饮食记录表';

-- -----------------------------------------------------------------------------
-- 表：meal_food_item
-- 用途：单餐食物明细行。
-- -----------------------------------------------------------------------------
CREATE TABLE meal_food_item (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  meal_id        BIGINT UNSIGNED NOT NULL COMMENT '饮食记录ID',
  food_id        BIGINT UNSIGNED NULL COMMENT '营养库ID可空',
  food_name      VARCHAR(64)     NOT NULL COMMENT '食物名称',
  weight_g       DECIMAL(8,1)    NULL COMMENT '重量克',
  carbs_g        DECIMAL(8,1)    NULL COMMENT '碳水克',
  calories_kcal  DECIMAL(8,1)    NULL COMMENT '热量kcal',
  sort_no        INT             NOT NULL DEFAULT 0 COMMENT '排序',
  created_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by     BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at     DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_meal (meal_id, sort_no),
  CONSTRAINT fk_mfi_meal FOREIGN KEY (meal_id) REFERENCES meal_record (id),
  CONSTRAINT chk_mfi_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mfi_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='饮食明细表';

-- -----------------------------------------------------------------------------
-- 表：chat_session
-- 用途：AI 助手会话头；含滚动摘要指针与默认模型，供多轮上下文续聊。
-- -----------------------------------------------------------------------------
CREATE TABLE chat_session (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  user_id            BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  title              VARCHAR(128)    NULL COMMENT '标题',
  session_status     TINYINT         NOT NULL DEFAULT 1 COMMENT '状态0归档1进行中2已结束',
  model_code         VARCHAR(64)     NULL COMMENT '默认模型编码如gpt-4o-mini',
  system_prompt_ver  VARCHAR(32)     NULL COMMENT '系统提示词版本',
  context_summary    MEDIUMTEXT      NULL COMMENT '会话滚动摘要(超长对话压缩后供模型续用)',
  summary_upto_msg_id BIGINT UNSIGNED NULL COMMENT '摘要已覆盖至的消息ID',
  window_msg_count   SMALLINT UNSIGNED NOT NULL DEFAULT 20 COMMENT '送入模型的最近原文消息条数',
  msg_count          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '消息条数冗余',
  last_msg_at        DATETIME(3)     NULL COMMENT '最近消息时间',
  created_by         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by         BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at         DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_user_last (user_id, last_msg_at),
  KEY idx_user_status (user_id, session_status),
  CONSTRAINT fk_cs_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT chk_cs_status CHECK (session_status IN (0, 1, 2)),
  CONSTRAINT chk_cs_window CHECK (window_msg_count BETWEEN 1 AND 200),
  CONSTRAINT chk_cs_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cs_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='聊天会话表';

-- -----------------------------------------------------------------------------
-- 表：chat_message
-- 用途：会话消息（文本/图片）；助手消息可挂本轮上下文快照与 token 计量。
-- -----------------------------------------------------------------------------
CREATE TABLE chat_message (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  session_id        BIGINT UNSIGNED NOT NULL COMMENT '会话ID',
  user_id           BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  msg_role          TINYINT         NOT NULL COMMENT '角色1用户2助手3系统',
  msg_type          TINYINT         NOT NULL DEFAULT 1 COMMENT '类型1文本2图片',
  content_text      TEXT            NULL COMMENT '文本内容',
  media_id          BIGINT UNSIGNED NULL COMMENT '图片文件ID',
  reply_to_msg_id   BIGINT UNSIGNED NULL COMMENT '回复的用户消息ID(助手消息常用)',
  model_code        VARCHAR(64)     NULL COMMENT '本条助手回复所用模型',
  prompt_tokens     INT UNSIGNED    NULL COMMENT '提示token数(助手消息)',
  completion_tokens INT UNSIGNED    NULL COMMENT '生成token数(助手消息)',
  finish_reason     TINYINT         NULL COMMENT '结束原因1正常2长度截断3内容过滤4错误',
  latency_ms        INT UNSIGNED    NULL COMMENT '模型耗时毫秒',
  client_msg_id     VARCHAR(64)     NULL COMMENT '客户端幂等ID；应用层必须捕获MySQL1062(Duplicate)当成功，勿抛给用户',
  created_by        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by        BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at        DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag       TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_client_active (session_id, client_msg_id, active_flag),
  KEY idx_session_time (session_id, created_at),
  KEY idx_reply_to (reply_to_msg_id),
  CONSTRAINT fk_cm_session FOREIGN KEY (session_id) REFERENCES chat_session (id),
  CONSTRAINT fk_cm_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_cm_media FOREIGN KEY (media_id) REFERENCES media_file (id),
  CONSTRAINT fk_cm_reply FOREIGN KEY (reply_to_msg_id) REFERENCES chat_message (id),
  CONSTRAINT chk_cm_role CHECK (msg_role IN (1, 2, 3)),
  CONSTRAINT chk_cm_type CHECK (msg_type IN (1, 2)),
  CONSTRAINT chk_cm_finish CHECK (finish_reason IS NULL OR finish_reason IN (1, 2, 3, 4)),
  CONSTRAINT chk_cm_payload CHECK (
    (msg_type = 1 AND content_text IS NOT NULL)
    OR (msg_type = 2 AND media_id IS NOT NULL)
  ),
  CONSTRAINT chk_cm_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cm_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='聊天消息表';

-- -----------------------------------------------------------------------------
-- 表：chat_context
-- 用途：AI 多轮对话上下文快照。每轮调用模型前写入本轮注入的上下文分片
--       （系统提示/健康档案/滚动摘要/近期血糖饮食/工具结果等），便于续聊、
--       审计与复现；与 chat_message 原文分离，避免把大块上下文塞进消息表。
-- -----------------------------------------------------------------------------
CREATE TABLE chat_context (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '上下文ID',
  session_id       BIGINT UNSIGNED NOT NULL COMMENT '会话ID',
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  trigger_msg_id   BIGINT UNSIGNED NULL COMMENT '触发本轮的用户消息ID',
  assistant_msg_id BIGINT UNSIGNED NULL COMMENT '本轮生成的助手消息ID(回写)',
  context_round    INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '会话内轮次号从1起',
  context_type     TINYINT         NOT NULL COMMENT '类型1系统提示2健康档案快照3历史摘要4业务数据5工具结果6其他',
  context_key      VARCHAR(64)     NULL COMMENT '键如system_prompt/health_profile/glucose_recent',
  content_text     MEDIUMTEXT      NULL COMMENT '文本上下文',
  content_json     JSON            NULL COMMENT '结构化上下文(健康档案/血糖饮食等)',
  token_estimate   INT UNSIGNED    NULL COMMENT '本分片预估token',
  model_code       VARCHAR(64)     NULL COMMENT '本轮调用模型编码',
  sort_no          INT             NOT NULL DEFAULT 0 COMMENT '同分片组内排序',
  created_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by       BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at       DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_session_round (session_id, context_round, sort_no),
  KEY idx_trigger_msg (trigger_msg_id),
  KEY idx_assistant_msg (assistant_msg_id),
  KEY idx_user_time (user_id, created_at),
  CONSTRAINT fk_cc_session FOREIGN KEY (session_id) REFERENCES chat_session (id),
  CONSTRAINT fk_cc_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_cc_trigger FOREIGN KEY (trigger_msg_id) REFERENCES chat_message (id),
  CONSTRAINT fk_cc_assistant FOREIGN KEY (assistant_msg_id) REFERENCES chat_message (id),
  CONSTRAINT chk_cc_type CHECK (context_type IN (1, 2, 3, 4, 5, 6)),
  CONSTRAINT chk_cc_payload CHECK (
    content_text IS NOT NULL OR content_json IS NOT NULL
  ),
  CONSTRAINT chk_cc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI对话上下文快照表';

-- -----------------------------------------------------------------------------
-- 表：feedback
-- 用途：用户留言；官方回复由运营写入，对接后台权限 feedback:reply。
-- -----------------------------------------------------------------------------
CREATE TABLE feedback (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  feedback_type   TINYINT         NOT NULL COMMENT '类型1建议2Bug3诉求4其他',
  content_text    VARCHAR(1000)   NOT NULL COMMENT '留言内容',
  reply_status    TINYINT         NOT NULL DEFAULT 0 COMMENT '状态0已收到1处理中2已处理3已关闭',
  official_reply  VARCHAR(1000)   NULL COMMENT '官方回复空则前端不展示',
  replied_by      BIGINT UNSIGNED NULL COMMENT '回复人运营账号ID',
  replied_at      DATETIME(3)     NULL COMMENT '回复时间',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_status_time (reply_status, created_at),
  CONSTRAINT fk_fb_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_fb_ops FOREIGN KEY (replied_by) REFERENCES ops_account (id),
  CONSTRAINT chk_fb_type CHECK (feedback_type IN (1, 2, 3, 4)),
  CONSTRAINT chk_fb_status CHECK (reply_status IN (0, 1, 2, 3)),
  CONSTRAINT chk_fb_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fb_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户反馈与官方回复表';

-- =============================================================================
-- 五、内容与工具
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 表：food_category
-- 用途：营养库食物分类（自 admin_schema 并入，按 wcy 审计规范改写）。
-- -----------------------------------------------------------------------------
CREATE TABLE food_category (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  category_name VARCHAR(32)     NOT NULL COMMENT '分类名：主食类/肉蛋类/蔬菜类等',
  sort_no       INT             NOT NULL DEFAULT 0 COMMENT '排序',
  is_enabled    TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用 0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  name_active   VARCHAR(32) GENERATED ALWAYS AS (IF(is_deleted = 0, category_name, NULL)) VIRTUAL COMMENT '未删除时分类名',
  PRIMARY KEY (id),
  UNIQUE KEY uk_name_active (name_active),
  KEY idx_sort (sort_no),
  CONSTRAINT chk_fc_enabled CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_fc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='食物分类表';

-- -----------------------------------------------------------------------------
-- 表：food_nutrition
-- 用途：食物营养库（每100g碳水/热量/GI）；category_code 兼容旧枚举，category_id 关联分类表。
-- -----------------------------------------------------------------------------
CREATE TABLE food_nutrition (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '食物ID',
  food_name           VARCHAR(64)     NOT NULL COMMENT '食物名称(应用层须trim规范化)',
  category_code       TINYINT         NOT NULL COMMENT '大类1主食2肉蛋3蔬菜4水果5奶6豆7坚果8饮料9零食',
  category_id         BIGINT UNSIGNED NULL COMMENT '分类ID(food_category.id)',
  carbs_per_100g      DECIMAL(8,1)    NOT NULL DEFAULT 0 COMMENT '每100g碳水g',
  calories_per_100g   DECIMAL(8,1)    NOT NULL DEFAULT 0 COMMENT '每100g热量kcal',
  gi_value            DECIMAL(5,1)    NULL COMMENT 'GI值可空',
  image_url           VARCHAR(512)    NULL COMMENT '缩略图',
  shelf_status        TINYINT         NOT NULL DEFAULT 1 COMMENT '上架0下架1上架',
  created_by          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by          BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at          DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag         TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_name (category_code, food_name, active_flag),
  KEY idx_cat_shelf (category_code, shelf_status),
  KEY idx_category_shelf (category_id, shelf_status),
  CONSTRAINT fk_fn_category FOREIGN KEY (category_id) REFERENCES food_category (id),
  CONSTRAINT chk_fn_cat CHECK (category_code BETWEEN 1 AND 9),
  CONSTRAINT chk_fn_shelf CHECK (shelf_status IN (0, 1)),
  CONSTRAINT chk_fn_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fn_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='食物营养库表';

ALTER TABLE meal_food_item
  ADD CONSTRAINT fk_mfi_food FOREIGN KEY (food_id) REFERENCES food_nutrition (id);

-- -----------------------------------------------------------------------------
-- 表：cms_banner
-- 用途：首页 Banner。
-- -----------------------------------------------------------------------------
CREATE TABLE cms_banner (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'BannerID',
  title         VARCHAR(64)     NOT NULL COMMENT '标题',
  subtitle      VARCHAR(128)    NULL COMMENT '副标题',
  image_url     VARCHAR(512)    NULL COMMENT '图片URL',
  link_url      VARCHAR(512)    NULL COMMENT '跳转URL',
  sort_no       INT             NOT NULL DEFAULT 0 COMMENT '排序升序',
  shelf_status  TINYINT         NOT NULL DEFAULT 1 COMMENT '0停用1启用',
  start_at      DATETIME(3)     NULL COMMENT '生效开始',
  end_at        DATETIME(3)     NULL COMMENT '生效结束',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_shelf_sort (shelf_status, sort_no),
  CONSTRAINT chk_cb_shelf CHECK (shelf_status IN (0, 1)),
  CONSTRAINT chk_cb_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_cb_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='首页Banner表';

-- -----------------------------------------------------------------------------
-- 表：cms_news
-- 用途：资讯图文/视频。
-- -----------------------------------------------------------------------------
CREATE TABLE cms_news (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资讯ID',
  title          VARCHAR(128)    NOT NULL COMMENT '标题',
  summary_text   VARCHAR(512)    NULL COMMENT '摘要',
  channel_code   TINYINT         NOT NULL COMMENT '频道1企业动态2行业快讯3设备新知4控糖科普',
  content_type   TINYINT         NOT NULL DEFAULT 1 COMMENT '类型1图文2视频',
  cover_url      VARCHAR(512)    NULL COMMENT '封面',
  video_url      VARCHAR(512)    NULL COMMENT '视频地址',
  content_body   MEDIUMTEXT      NULL COMMENT '正文',
  publish_at     DATETIME(3)     NOT NULL COMMENT '发布时间',
  publish_status TINYINT         NOT NULL DEFAULT 0 COMMENT '0草稿1已发布2下架',
  like_count     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞冗余计数(需定期与news_user_action校准)',
  collect_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '收藏冗余计数(需定期与news_user_action校准)',
  created_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by     BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at     DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
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

-- -----------------------------------------------------------------------------
-- 表：news_user_action
-- 用途：用户对资讯的点赞/收藏。
-- -----------------------------------------------------------------------------
CREATE TABLE news_user_action (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  news_id       BIGINT UNSIGNED NOT NULL COMMENT '资讯ID',
  is_liked      TINYINT         NOT NULL DEFAULT 0 COMMENT '是否点赞0否1是',
  is_collected  TINYINT         NOT NULL DEFAULT 0 COMMENT '是否收藏0否1是',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  active_flag   TINYINT GENERATED ALWAYS AS (IF(is_deleted = 0, 1, NULL)) VIRTUAL COMMENT '有效标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_news_active (user_id, news_id, active_flag),
  CONSTRAINT fk_nua_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT fk_nua_news FOREIGN KEY (news_id) REFERENCES cms_news (id),
  CONSTRAINT chk_nua_flags CHECK (is_liked IN (0, 1) AND is_collected IN (0, 1)),
  CONSTRAINT chk_nua_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_nua_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='资讯点赞收藏表';

-- -----------------------------------------------------------------------------
-- 表：data_export_task
-- 用途：导出任务（含自定义日期）。
-- -----------------------------------------------------------------------------
CREATE TABLE data_export_task (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  range_type    TINYINT         NOT NULL COMMENT '范围1近7天2近30天3自定义',
  start_date    DATE            NULL COMMENT '开始日期',
  end_date      DATE            NULL COMMENT '结束日期',
  file_url      VARCHAR(1024)   NULL COMMENT '结果文件',
  summary_text  MEDIUMTEXT      NULL COMMENT '文本摘要',
  task_status   TINYINT         NOT NULL DEFAULT 0 COMMENT '0待处理1进行中2成功3失败',
  error_msg     VARCHAR(512)    NULL COMMENT '失败原因',
  finished_at   DATETIME(3)     NULL COMMENT '完成时间',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_status_time (task_status, created_at),
  CONSTRAINT fk_det_user FOREIGN KEY (user_id) REFERENCES user_account (id),
  CONSTRAINT chk_det_range CHECK (range_type IN (1, 2, 3)),
  CONSTRAINT chk_det_st CHECK (task_status IN (0, 1, 2, 3)),
  CONSTRAINT chk_det_custom CHECK (
    range_type <> 3 OR (start_date IS NOT NULL AND end_date IS NOT NULL AND start_date <= end_date)
  ),
  CONSTRAINT chk_det_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_det_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='数据导出任务表';

-- -----------------------------------------------------------------------------
-- 表：data_backup
-- 用途：用户备份快照。
-- -----------------------------------------------------------------------------
CREATE TABLE data_backup (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '备份ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  backup_version  INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '格式版本',
  storage_type    TINYINT         NOT NULL DEFAULT 1 COMMENT '1内嵌JSON2对象存储；超约1MB建议走对象存储',
  payload_json    LONGTEXT        NULL COMMENT '内嵌JSON(应用层宜限制大小，过大改走对象存储)',
  object_url      VARCHAR(1024)   NULL COMMENT '对象URL',
  size_bytes      BIGINT UNSIGNED NULL COMMENT '大小',
  checksum_sha256 CHAR(64)        NULL COMMENT '校验和',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  CONSTRAINT fk_db_user FOREIGN KEY (user_id) REFERENCES user_account (id),
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

-- -----------------------------------------------------------------------------
-- 表：sys_config
-- 用途：系统参数键值配置（自 admin_schema 并入，按 wcy 审计规范改写）。
-- -----------------------------------------------------------------------------
CREATE TABLE sys_config (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  config_key     VARCHAR(64)     NOT NULL COMMENT '参数键如glucose.target.low',
  config_value   VARCHAR(2000)   NULL COMMENT '参数值(JSON或标量)',
  value_type     TINYINT         NOT NULL DEFAULT 1 COMMENT '类型1字符串2数字3布尔4JSON',
  config_group   TINYINT         NOT NULL DEFAULT 1 COMMENT '分组1血糖2外观3通知4文案5通用',
  remark         VARCHAR(255)    NULL COMMENT '说明',
  modified_by_name VARCHAR(64)   NULL COMMENT '最后修改人账号冗余',
  created_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by     BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at     DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  key_active     VARCHAR(64) GENERATED ALWAYS AS (IF(is_deleted = 0, config_key, NULL)) VIRTUAL COMMENT '未删除时参数键',
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key_active (key_active),
  KEY idx_group (config_group),
  CONSTRAINT chk_sc_type CHECK (value_type IN (1, 2, 3, 4)),
  CONSTRAINT chk_sc_group CHECK (config_group IN (1, 2, 3, 4, 5)),
  CONSTRAINT chk_sc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_sc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='系统参数配置表';

-- -----------------------------------------------------------------------------
-- 表：ops_audit_log
-- 用途：运营操作审计（只追加；增长用按月分区 DROP，勿逻辑删除）。
--       合并 admin sys_operation_log 的请求上下文字段。
-- -----------------------------------------------------------------------------
CREATE TABLE ops_audit_log (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审计ID',
  ops_id           BIGINT UNSIGNED NOT NULL COMMENT '运营账号ID',
  operator_name    VARCHAR(64)     NULL COMMENT '操作人账号冗余',
  role_code        VARCHAR(64)     NULL COMMENT '操作时角色编码',
  module_code      VARCHAR(32)     NOT NULL COMMENT '模块news/banner/media/food/user/health/feedback/export/system',
  action_code      VARCHAR(64)     NOT NULL COMMENT '动作编码如feedback_reply/news_publish',
  target_type      VARCHAR(32)     NULL COMMENT '目标类型如feedback',
  target_id        BIGINT UNSIGNED NULL COMMENT '目标主键',
  detail_json      VARCHAR(2000)   NULL COMMENT '详情JSON',
  request_uri      VARCHAR(255)    NULL COMMENT '请求URI',
  request_method   VARCHAR(10)     NULL COMMENT '请求方法',
  client_ip        VARCHAR(45)     NULL COMMENT 'IP',
  cost_ms          INT             NULL COMMENT '耗时毫秒',
  result_code      INT             NULL COMMENT '业务返回码',
  created_by       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人(通常等于ops_id)',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_ops_time (ops_id, created_at),
  KEY idx_module_action (module_code, action_code),
  KEY idx_created (created_at),
  KEY idx_target (target_type, target_id),
  CONSTRAINT fk_oal_ops FOREIGN KEY (ops_id) REFERENCES ops_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运营操作审计表(只追加)';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 触发器：血糖仅非餐点；饮食仅餐点
-- 说明：作为最后防线保留。设备批量导入场景请先在应用层校验 node_code，
--       避免逐行触发器查询 dict 带来的性能开销。
-- =============================================================================
DELIMITER $$

DROP TRIGGER IF EXISTS trg_gr_not_meal $$
CREATE TRIGGER trg_gr_not_meal BEFORE INSERT ON glucose_record FOR EACH ROW
BEGIN
  DECLARE v TINYINT;
  SELECT is_meal INTO v FROM dict_glucose_node WHERE node_code = NEW.node_code AND is_deleted = 0;
  IF v IS NULL OR v = 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'glucose_record requires non-meal node';
  END IF;
END $$

DROP TRIGGER IF EXISTS trg_gr_not_meal_u $$
CREATE TRIGGER trg_gr_not_meal_u BEFORE UPDATE ON glucose_record FOR EACH ROW
BEGIN
  DECLARE v TINYINT;
  SELECT is_meal INTO v FROM dict_glucose_node WHERE node_code = NEW.node_code AND is_deleted = 0;
  IF v IS NULL OR v = 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'glucose_record requires non-meal node';
  END IF;
END $$

DROP TRIGGER IF EXISTS trg_mr_is_meal $$
CREATE TRIGGER trg_mr_is_meal BEFORE INSERT ON meal_record FOR EACH ROW
BEGIN
  DECLARE v TINYINT;
  SELECT is_meal INTO v FROM dict_glucose_node WHERE node_code = NEW.node_code AND is_deleted = 0;
  IF v IS NULL OR v = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'meal_record requires meal node';
  END IF;
END $$

DROP TRIGGER IF EXISTS trg_mr_is_meal_u $$
CREATE TRIGGER trg_mr_is_meal_u BEFORE UPDATE ON meal_record FOR EACH ROW
BEGIN
  DECLARE v TINYINT;
  SELECT is_meal INTO v FROM dict_glucose_node WHERE node_code = NEW.node_code AND is_deleted = 0;
  IF v IS NULL OR v = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'meal_record requires meal node';
  END IF;
END $$

DELIMITER ;

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
('operator', '运营', 2, '业务运营：资讯/轮播/媒体/营养库/反馈/导出；无用户与系统管理'),
('editor', '内容编辑', 3, '内容向：资讯/轮播/媒体/营养库');

INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id, path_url, sort_no) VALUES
('dashboard', '数据看板', 1, 0, '/admin/dashboard', 1),
('dashboard:view', '看板查看', 3, 0, '/api/admin/dashboard', 2),
('news', '资讯管理', 1, 0, '/admin/news', 10),
('news:list', '资讯列表', 3, 0, '/api/admin/news', 11),
('news:publish', '发布资讯', 2, 0, '/api/admin/news/publish', 12),
('banner', 'Banner管理', 1, 0, '/admin/banner', 20),
('banner:manage', '轮播维护', 2, 0, '/api/admin/banners', 21),
('media', '媒体素材', 1, 0, '/admin/media', 30),
('media:manage', '素材维护', 2, 0, '/api/admin/media', 31),
('food', '营养库管理', 1, 0, '/admin/food', 40),
('food:manage', '营养库维护', 2, 0, '/api/admin/foods', 41),
('feedback', '反馈管理', 1, 0, '/admin/feedback', 50),
('feedback:list', '反馈列表', 3, 0, '/api/admin/feedbacks', 51),
('feedback:reply', '官方回复', 2, 0, '/api/admin/feedbacks/reply', 52),
('export', '导出任务', 1, 0, '/admin/exports', 60),
('export:manage', '导出任务处理', 2, 0, '/api/admin/exports', 61),
('user', '用户管理', 1, 0, '/admin/users', 70),
('user:manage', '用户维护', 2, 0, '/api/admin/users', 71),
('health', '健康档案', 1, 0, '/admin/archives', 80),
('health:view', '档案查看', 3, 0, '/api/admin/archives', 81),
('system', '系统管理', 1, 0, '/admin/system', 90),
('system:role', '角色权限', 2, 0, '/api/admin/roles', 91),
('system:config', '系统参数', 2, 0, '/api/admin/configs', 92),
('system:log', '操作日志', 3, 0, '/api/admin/logs', 93);

-- 【开发种子】生产环境请删除下方 ops 账号相关 INSERT，改走管理员创建流程。
-- 此处账号默认禁用，password_hash 为不可用占位，禁止直接上线使用。
INSERT INTO ops_account (id, username, password_hash, account_status) VALUES
(1, 'admin', '!invalid-hash-replace-via-bootstrap', 0);

INSERT INTO ops_profile (ops_id, real_name) VALUES (1, '系统管理员');

INSERT INTO sys_ops_role (ops_id, role_id) VALUES (1, 1);

-- 超管：全部权限
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 1, id FROM sys_permission WHERE is_deleted = 0;

-- 运营：除用户/健康档案/系统管理外的业务权限
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 2, id FROM sys_permission
WHERE is_deleted = 0
  AND perm_code IN (
    'dashboard', 'dashboard:view',
    'news', 'news:list', 'news:publish',
    'banner', 'banner:manage',
    'media', 'media:manage',
    'food', 'food:manage',
    'feedback', 'feedback:list', 'feedback:reply',
    'export', 'export:manage'
  );

-- 内容编辑：资讯/轮播/媒体/营养库
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 3, id FROM sys_permission
WHERE is_deleted = 0
  AND perm_code IN (
    'dashboard', 'dashboard:view',
    'news', 'news:list', 'news:publish',
    'banner', 'banner:manage',
    'media', 'media:manage',
    'food', 'food:manage'
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

-- value_type: 1字符串 2数字 3布尔 4JSON；config_group: 1血糖 2外观 3通知 4文案 5通用
INSERT INTO sys_config (config_key, config_value, value_type, config_group, remark) VALUES
('glucose.target.low',  '3.9',  2, 1, '目标区间下限(mmol/L)'),
('glucose.target.high', '7.8',  2, 1, '餐后目标上限(mmol/L)'),
('glucose.danger.high', '10',   2, 1, '需关注阈值(mmol/L)'),
('appearance.theme',    '1',    2, 2, '默认主题 1绿2青绿3简洁'),
('appearance.fontSize', '2',    2, 2, '默认字号 1标准2较大3更大'),
('notify.glucose',      '1',    3, 3, '血糖提醒默认开关'),
('notify.meal',         '1',    3, 3, '饮食记录提醒默认开关'),
('notify.news',         '0',    3, 3, '资讯推送默认开关'),
('text.disclaimer',     '本系统提供的血糖估算、饮食建议与 AI 问答仅供健康管理参考，不构成医疗诊断或治疗建议；如有不适请及时就医。', 1, 4, '免责声明'),
('text.about',          '控糖健康管理系统（AI控糖助手）由贵州慧科未来科技有限公司运营。', 1, 4, '关于我们');

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
