-- User list removal must retain the original task and PDF for operations.
ALTER TABLE data_export_task ADD COLUMN user_deleted_at DATETIME(3) NULL;
CREATE INDEX idx_export_user_visible ON data_export_task(user_id,is_deleted,user_deleted_at,id);
