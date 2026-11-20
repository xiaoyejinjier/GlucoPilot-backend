-- 扩展设备类型闭集：穿戴、泵笔、体测等；存量 CGM/BGM/HUB/OTHER 仍合法。

ALTER TABLE user_device
  DROP CHECK chk_ud_category;

ALTER TABLE user_device
  MODIFY COLUMN device_category VARCHAR(32) NULL
    COMMENT '设备类型 CGM/BGM/PUMP/PEN/BAND/WATCH/RING/HUB/SCALE/BP/OXIMETER/OTHER';

ALTER TABLE user_device
  ADD CONSTRAINT chk_ud_category CHECK (
    device_category IS NULL
    OR device_category IN (
      'CGM', 'BGM', 'PUMP', 'PEN', 'BAND', 'WATCH', 'RING',
      'HUB', 'SCALE', 'BP', 'OXIMETER', 'OTHER'
    )
  );
