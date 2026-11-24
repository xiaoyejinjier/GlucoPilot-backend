-- 设备同步样本快照：与 device_sync_log 同事务写入，供管理端只读血糖曲线。

CREATE TABLE device_sync_sample (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '快照ID',
  sync_log_id     BIGINT UNSIGNED NOT NULL COMMENT 'device_sync_log.id',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT 'C 端用户ID',
  device_id       BIGINT UNSIGNED NOT NULL COMMENT 'user_device.id',
  recorded_at     DATETIME(3)     NOT NULL COMMENT '样本测量时间',
  node_code       VARCHAR(32)     NOT NULL COMMENT '血糖节点编码',
  glucose_value   DECIMAL(4,1)    NOT NULL COMMENT '血糖mmol/L',
  created_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by      BIGINT UNSIGNED NULL COMMENT '删除人',
  deleted_at      DATETIME(3)     NULL COMMENT '删除时间',
  is_deleted      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_dss PRIMARY KEY (id),
  KEY idx_dss_sync_log (sync_log_id),
  KEY idx_dss_device_time (device_id, recorded_at),
  CONSTRAINT chk_dss_glucose CHECK (glucose_value > 0 AND glucose_value < 40),
  CONSTRAINT chk_dss_node CHECK (node_code <> ''),
  CONSTRAINT chk_dss_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_dss_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='设备同步血糖样本快照';
