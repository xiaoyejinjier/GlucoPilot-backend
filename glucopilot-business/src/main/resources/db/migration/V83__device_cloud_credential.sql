-- 厂商云 OAuth 凭证：access/refresh 仅存应用层密文，接口永不回传明文。

CREATE TABLE device_cloud_credential (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id            BIGINT UNSIGNED NOT NULL COMMENT 'C 端用户ID',
  device_id          BIGINT UNSIGNED NULL COMMENT '关联 user_device.id，可空（先授权后绑设备）',
  provider           VARCHAR(32)     NOT NULL COMMENT '厂商编码，首期 HUAWEI',
  vendor_subject     VARCHAR(128)    NOT NULL COMMENT '厂商侧 openid/union 标识',
  access_token_enc   TEXT            NOT NULL COMMENT 'access_token 密文',
  refresh_token_enc  TEXT            NULL COMMENT 'refresh_token 密文',
  token_expires_at   DATETIME(3)     NULL COMMENT 'access_token 过期时间',
  scopes             VARCHAR(512)    NULL COMMENT '授权 scope 原文',
  status             TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1有效 0撤销',
  created_by         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by         BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at         DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_dcc PRIMARY KEY (id),
  UNIQUE KEY uk_dcc_user_provider_subject (user_id, provider, vendor_subject, is_deleted),
  KEY idx_dcc_user_provider (user_id, provider),
  KEY idx_dcc_device (device_id),
  CONSTRAINT chk_dcc_provider CHECK (provider <> ''),
  CONSTRAINT chk_dcc_subject CHECK (vendor_subject <> ''),
  CONSTRAINT chk_dcc_status CHECK (status IN (0, 1)),
  CONSTRAINT chk_dcc_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_dcc_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='厂商云 OAuth 凭证表';
