-- 设备同步流水：每次 glucose-sync 成功且带绑定设备时追加一条。

CREATE TABLE device_sync_log (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT 'C 端用户ID',
  device_id       BIGINT UNSIGNED NOT NULL COMMENT 'user_device.id',
  provider        VARCHAR(32)     NOT NULL COMMENT '厂商编码',
  sample_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '本次样本条数',
  created_count   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '新建血糖记录条数',
  remark          VARCHAR(255)    NULL COMMENT '短摘要',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同步时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_dsl PRIMARY KEY (id),
  KEY idx_dsl_device_time (device_id, gmt_create),
  KEY idx_dsl_user (user_id),
  CONSTRAINT chk_dsl_provider CHECK (provider <> ''),
  CONSTRAINT chk_dsl_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_dsl_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='设备同步流水表';
