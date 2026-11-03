-- 食品缓存按数据库目录身份和已提交版本分代，避免跨目录串用或并发回填旧数据。
CREATE TABLE food_catalog_cache_version (
    id TINYINT NOT NULL PRIMARY KEY,
    cache_namespace CHAR(36) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_food_cache_singleton CHECK (id = 1),
    CONSTRAINT chk_food_cache_revision CHECK (revision > 0)
);

INSERT INTO food_catalog_cache_version (id, cache_namespace, revision)
VALUES (1, UUID(), 1);
