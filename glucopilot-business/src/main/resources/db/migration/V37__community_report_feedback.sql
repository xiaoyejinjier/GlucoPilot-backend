ALTER TABLE community_report ADD COLUMN target_title VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE community_report ADD COLUMN feedback_read_at DATETIME(3) NULL;

CREATE INDEX idx_cr_reporter_feedback ON community_report(reporter_id,feedback_read_at,state,id);
