-- 设备产品分类与型号：与 provider（适配器路由）分离；可空兼容存量。

ALTER TABLE user_device
  ADD COLUMN device_category VARCHAR(32) NULL COMMENT '设备类型 CGM/BGM/HUB/OTHER' AFTER provider,
  ADD COLUMN device_model VARCHAR(64) NULL COMMENT '设备型号/SKU' AFTER device_category;

ALTER TABLE user_device
  ADD CONSTRAINT chk_ud_category CHECK (
    device_category IS NULL
    OR device_category IN ('CGM', 'BGM', 'HUB', 'OTHER')
  );
