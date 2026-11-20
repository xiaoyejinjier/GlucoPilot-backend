-- 反馈类型独立维护；保留历史编号，设备支持使用5。
CREATE TABLE feedback_type (
  id TINYINT UNSIGNED NOT NULL PRIMARY KEY COMMENT '反馈类型编号',
  name VARCHAR(32) NOT NULL COMMENT '显示名称',
  device_related TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否需要设备信息',
  enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可提交',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '显示顺序',
  CONSTRAINT chk_feedback_type_flags CHECK (device_related IN (0,1) AND enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈类型字典';

INSERT INTO feedback_type(id,name,device_related,enabled,sort_no) VALUES
  (1,'产品建议',0,1,10),(2,'Bug问题',0,1,20),(3,'功能诉求',0,1,30),
  (4,'其他',0,1,40),(5,'设备支持',1,1,50);

ALTER TABLE feedback
  DROP CHECK chk_fb_type,
  ADD COLUMN device_id BIGINT UNSIGNED NULL COMMENT '关联设备，可空' AFTER feedback_type,
  ADD COLUMN device_description VARCHAR(120) NULL COMMENT '提交时设备名称或品牌型号快照' AFTER device_id,
  ADD KEY idx_feedback_device (device_id),
  ADD CONSTRAINT fk_feedback_type FOREIGN KEY(feedback_type) REFERENCES feedback_type(id),
  ADD CONSTRAINT fk_feedback_device FOREIGN KEY(device_id) REFERENCES user_device(id) ON DELETE SET NULL;
