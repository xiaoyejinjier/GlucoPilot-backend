CREATE TABLE doctor_ops_binding (
    doctor_user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    ops_id BIGINT UNSIGNED NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_doctor_ops_identity(ops_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生小程序身份与后台账号固定绑定';

INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark)
SELECT '医生','doctor',5,'5','0','0','migration',NOW(),'医生后台独立角色，仅授权医生功能'
WHERE NOT EXISTS(SELECT 1 FROM sys_role WHERE role_key='doctor');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '医生管理',0,6,'/doctor-management','M','0','0','','Service','migration',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='/doctor-management');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT item.name,parent.menu_id,item.sort_no,item.path,'C','0','0',item.perm,item.icon,'migration',NOW()
FROM (
    SELECT '医生审核' name,1 sort_no,'/doctor-management/reviews' path,'doctor:review:manage' perm,'DocumentChecked' icon
    UNION ALL SELECT '医生账号',2,'/doctor-management/accounts','doctor:account:manage','User'
    UNION ALL SELECT '接诊工作台',3,'/doctor-management/workbench','doctor:workbench:view','Service'
    UNION ALL SELECT '我的问诊',4,'/doctor-management/consultations','doctor:consultation:manage','ChatLineSquare'
    UNION ALL SELECT '我的收入',5,'/doctor-management/income','doctor:income:view','Wallet'
) item JOIN sys_menu parent ON parent.path='/doctor-management'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu old WHERE old.perms=item.perm);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
    ON m.perms IN ('doctor:workbench:view','doctor:consultation:manage','doctor:income:view')
WHERE r.role_key='doctor' AND r.del_flag='0'
    AND NOT EXISTS(SELECT 1 FROM sys_role_menu old WHERE old.role_id=r.role_id AND old.menu_id=m.menu_id);
