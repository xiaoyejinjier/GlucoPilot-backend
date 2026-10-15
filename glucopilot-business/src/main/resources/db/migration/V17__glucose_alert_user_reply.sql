ALTER TABLE gp_glucose_alert_case
  ADD COLUMN user_reply VARCHAR(1000) NULL COMMENT '给用户的公开回复，内部note不公开';

ALTER TABLE gp_glucose_alert_case
  ADD COLUMN replied_at DATETIME(3) NULL COMMENT '最近公开回复时间';

ALTER TABLE gp_glucose_alert_log
  ADD COLUMN user_reply VARCHAR(1000) NULL COMMENT '本次操作后的公开回复快照';
