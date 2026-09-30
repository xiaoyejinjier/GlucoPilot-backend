-- 重定义 media_file.storage_provider 枚举语义：
-- 旧 1本地 / 2COS / 3OSS  →  新 1=MinIO / 2=阿里云OSS
-- dev 空库直接执行；生产若有旧 storage_provider=3（COS）数据，由运维先回填（见迁移计划 §4/§13）。

ALTER TABLE media_file
    MODIFY COLUMN storage_provider TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '存储1MinIO 2阿里云OSS';

ALTER TABLE media_file DROP CHECK chk_mf_prov;
ALTER TABLE media_file ADD CONSTRAINT chk_mf_prov CHECK (storage_provider IN (1, 2));