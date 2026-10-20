-- C 端邮箱验证码鉴权：账号邮箱字段、验证码表、会话 login_type=3。

ALTER TABLE user_account
  ADD COLUMN email VARCHAR(128) NULL COMMENT '绑定邮箱' AFTER phone;

ALTER TABLE user_account
  ADD UNIQUE KEY uk_email_active (email, is_deleted),
  ADD CONSTRAINT chk_ua_email CHECK (email IS NULL OR email <> '');

CREATE TABLE auth_email_code (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  email           VARCHAR(128)    NOT NULL COMMENT '邮箱',
  verify_code     CHAR(6)         NOT NULL COMMENT '6位验证码',
  expire_at       DATETIME(3)     NOT NULL COMMENT '过期时间',
  is_used         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已使用0否1是',
  client_ip       VARCHAR(45)     NULL COMMENT '请求IP',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_email_time (email, gmt_create),
  KEY idx_expire (expire_at),
  CONSTRAINT chk_aec_email CHECK (email <> ''),
  CONSTRAINT chk_aec_used CHECK (is_used IN (0, 1)),
  CONSTRAINT chk_aec_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_aec_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='邮箱验证码表';

ALTER TABLE user_session
  DROP CHECK chk_usess_login;

ALTER TABLE user_session
  ADD CONSTRAINT chk_usess_login CHECK (login_type IN (1, 2, 3));

ALTER TABLE user_session
  MODIFY COLUMN login_type TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '登录方式1微信2手机号3邮箱';
