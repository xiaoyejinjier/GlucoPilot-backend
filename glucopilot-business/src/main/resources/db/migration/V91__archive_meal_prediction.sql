-- 阶段 4：餐后血糖预测下线。
-- 说明：方案文档写作 V63，但 V63 已被 admin_reminder_reads_and_measurement_snapshots 占用，
-- 按"顺延避免重号"使用当前最新版本 +1（V91）。
-- 只做历史归档快照：不修改 meal_record 预测列，便于过渡期后统一删除。

CREATE TABLE meal_prediction_archive (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  meal_record_id        BIGINT UNSIGNED NOT NULL COMMENT '原饮食记录 ID',
  user_id               BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
  record_date           DATE            NOT NULL COMMENT '业务日期',
  node_code             VARCHAR(32)     NOT NULL COMMENT '餐点节点',
  predict_now           DECIMAL(4,1)    NULL COMMENT '预测当前血糖(mmol/L)',
  predict_suggest       DECIMAL(4,1)    NULL COMMENT '预测建议血糖(mmol/L)',
  suggest_text          VARCHAR(512)    NULL COMMENT '预测建议文案',
  algorithm_version     VARCHAR(8)      NULL COMMENT '算法版本',
  recommendation_status VARCHAR(16)     NULL COMMENT '推荐状态',
  meal_recorded_at      DATETIME(3)     NULL COMMENT '原记录确认时间',
  archived_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '归档时间',
  PRIMARY KEY (id),
  KEY idx_mra_user_date (user_id, record_date),
  KEY idx_mra_meal (meal_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐后血糖预测历史归档（阶段 4）';

INSERT INTO meal_prediction_archive (
    meal_record_id, user_id, record_date, node_code,
    predict_now, predict_suggest, suggest_text, algorithm_version, recommendation_status,
    meal_recorded_at)
SELECT id, user_id, record_date, node_code,
       predict_now, predict_suggest, suggest_text, algorithm_version, recommendation_status,
       recorded_at
FROM meal_record
WHERE is_deleted = 0
  AND (predict_now IS NOT NULL
    OR predict_suggest IS NOT NULL
    OR suggest_text IS NOT NULL
    OR algorithm_version IS NOT NULL
    OR recommendation_status IS NOT NULL);
