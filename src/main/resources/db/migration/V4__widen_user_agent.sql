-- =============================================================================
-- 放宽 user_session / ops_session 的 user_agent 到 512
--
-- 背景：V1__init_schema.sql 里这两列早已是 VARCHAR(512)，但既有库是「已有表 + 无
-- flyway_schema_history」被 baseline 到 3 的，改动过的 V1 不会在既有库上重跑，
-- 于是线上仍是 VARCHAR(256)：微信开发者工具的 UA 超过 256 字符 → 登录写 session 直接
-- Data too long for column 'user_agent'，表现为「一键登录 500」。
--
-- 幂等：列已经是 512 时执行无副作用；新建库由 V1 建成 512，这里执行同样无副作用。
-- =============================================================================

ALTER TABLE user_session
  MODIFY COLUMN user_agent VARCHAR(512) NULL COMMENT 'UA（微信开发者工具等环境可能较长）';

ALTER TABLE ops_session
  MODIFY COLUMN user_agent VARCHAR(512) NULL COMMENT 'UA（浏览器/客户端环境可能较长）';
