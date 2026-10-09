-- Flyway V4: 补 C 端免责声明版本参数（不改表结构）
-- 目的：/api/v1/users/me/settings/disclaimer-agreement 需要与 sys_config 的当前文案版本比对；
--       种子数据里 user_setting.disclaimer_version 已是 2026.09.14，这里补齐对应参数。
-- 幂等：sys_config 有 uk_config_key_active 唯一键，重复执行不会冲突。

INSERT INTO sys_config (config_key, config_value, value_type, config_group, remark)
SELECT 'text.disclaimer.version', '2026.09.14', 1, 'TEXT', '免责声明文案版本号，与 user_setting.disclaimer_version 比对'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM sys_config WHERE config_key = 'text.disclaimer.version' AND is_deleted = 0
);
