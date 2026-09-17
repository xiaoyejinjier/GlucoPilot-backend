-- Flyway V3: dev B-end accounts (admin/admin123, editor01/editor123)

-- 联调：启用运营账号
-- admin / admin123
-- editor01 / editor123

UPDATE ops_account
SET password_hash = '$2b$10$.zXatx4fOY7T2on.JmZyVOoBvOEJH3dlZCXEBLQuzoXjACuEPO6g6',
    account_status = 1,
    gmt_modified = CURRENT_TIMESTAMP(3)
WHERE username = 'admin' AND is_deleted = 0;

INSERT INTO ops_account (username, password_hash, account_status)
SELECT 'editor01', '$2b$10$IgJVoyBmsLITsbjlwqAUIeR8snVMoKUSOUCCc0GZM0Fg2offrd6Fq', 1
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM ops_account WHERE username = 'editor01' AND is_deleted = 0
);

UPDATE ops_account
SET password_hash = '$2b$10$IgJVoyBmsLITsbjlwqAUIeR8snVMoKUSOUCCc0GZM0Fg2offrd6Fq',
    account_status = 1
WHERE username = 'editor01' AND is_deleted = 0;

INSERT INTO ops_profile (ops_id, real_name)
SELECT a.id, 'Editor'
FROM ops_account a
WHERE a.username = 'editor01' AND a.is_deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM ops_profile p WHERE p.ops_id = a.id AND p.is_deleted = 0
  );

-- 运营编辑 → operator（role_id=2）；无绑定则补一条
INSERT INTO sys_ops_role (ops_id, role_id)
SELECT a.id, r.id
FROM ops_account a
CROSS JOIN sys_role r
WHERE a.username = 'editor01' AND a.is_deleted = 0
  AND r.role_code = 'operator' AND r.is_deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_ops_role sor
    WHERE sor.ops_id = a.id AND sor.is_deleted = 0
  );
