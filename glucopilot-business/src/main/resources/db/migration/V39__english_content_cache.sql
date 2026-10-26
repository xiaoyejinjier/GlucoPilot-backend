CREATE TABLE content_localization (
  kind VARCHAR(24) NOT NULL,
  resource_id VARCHAR(24) NOT NULL,
  source_hash CHAR(64) NOT NULL,
  locale VARCHAR(8) NOT NULL DEFAULT 'en',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  translated_json MEDIUMTEXT NULL,
  lease_token VARCHAR(36) NULL,
  lease_until DATETIME(3) NULL,
  retry_after DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(kind,resource_id,source_hash,locale),
  KEY idx_cl_lease(status,lease_until),
  CONSTRAINT chk_cl_kind CHECK(kind IN ('ARTICLE_PREVIEW','ARTICLE_BODY','PROFILE_NAME')),
  CONSTRAINT chk_cl_status CHECK(status IN ('PENDING','READY','FAILED'))
);
