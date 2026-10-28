-- =============================================================================
--   1. 新建 user_account_cancellation，记录申请/执行/恢复事实（合规举证）；
--   2. 放宽 user_account.openid 的 NOT NULL，注销时置空以释放唯一键；
--   3. 扩展 account_status 的 CHECK，新增 3=已注销、4=已注销可恢复。
-- =============================================================================

CREATE TABLE user_account_cancellation (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '注销凭据ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户账号ID',
  mode            TINYINT UNSIGNED NOT NULL COMMENT '模式 1立即注销 2七天可恢复',
  status          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态 0待执行 1执行中 2已执行 3已恢复 4失败',
  reason_code     TINYINT UNSIGNED NULL COMMENT '原因 1不再使用 2隐私顾虑 3体验不佳 4其他',
  verify_channel  TINYINT UNSIGNED NOT NULL COMMENT '验证通道 1短信 2邮箱',
  requested_at    DATETIME(3)     NOT NULL COMMENT '申请时间',
  isolate_until   DATETIME(3)     NULL COMMENT '可恢复截止时间(仅模式2)',
  executed_at     DATETIME(3)     NULL COMMENT '清理完成时间',
  recovered_at    DATETIME(3)     NULL COMMENT '恢复时间',
  fail_count      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '失败重试次数',
  last_error      VARCHAR(512)    NULL COMMENT '最近失败原因(不含健康内容)',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at    DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  KEY idx_uac_user_status (user_id, status),
  KEY idx_uac_status_isolate (status, isolate_until),
  CONSTRAINT chk_uac_mode CHECK (mode IN (1, 2)),
  CONSTRAINT chk_uac_status CHECK (status IN (0, 1, 2, 3, 4)),
  CONSTRAINT chk_uac_reason CHECK (reason_code IS NULL OR reason_code IN (1, 2, 3, 4)),
  CONSTRAINT chk_uac_channel CHECK (verify_channel IN (1, 2)),
  CONSTRAINT chk_uac_isolate CHECK (
    (mode = 1 AND isolate_until IS NULL)
    OR (mode = 2 AND isolate_until IS NOT NULL)
  ),
  CONSTRAINT chk_uac_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_uac_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='账号注销凭据表';

-- 注销时 openid 置空释放 uk_openid_active；
-- chk_ua_openid CHECK (openid <> '') 对 NULL 返回 UNKNOWN，可通过，无需删除。
ALTER TABLE user_account
  MODIFY COLUMN openid VARCHAR(64) NULL COMMENT '微信 openid(注销后置空释放唯一键)';

-- account_status 新增 3=已注销、4=已注销可恢复
ALTER TABLE user_account DROP CHECK chk_ua_status;
ALTER TABLE user_account
  ADD CONSTRAINT chk_ua_status CHECK (account_status IN (0, 1, 2, 3, 4));
