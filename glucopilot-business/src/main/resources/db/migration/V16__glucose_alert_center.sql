-- 异常来源仍是 glucose_record；仅持久化运营跟进和审计，不改历史血糖。
CREATE TABLE gp_glucose_alert_case (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  record_id BIGINT UNSIGNED NOT NULL,
  glucose_value DECIMAL(4,1) NOT NULL,
  recorded_at DATETIME(3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  note VARCHAR(1000) NOT NULL,
  handled_by BIGINT UNSIGNED NOT NULL,
  handled_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_alert_snapshot(record_id, glucose_value, recorded_at),
  CONSTRAINT chk_alert_status CHECK(status IN ('PROCESSING','RESOLVED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异常血糖快照处理结果';

CREATE TABLE gp_glucose_alert_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  record_id BIGINT UNSIGNED NOT NULL,
  glucose_value DECIMAL(4,1) NOT NULL,
  recorded_at DATETIME(3) NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  note VARCHAR(1000) NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  operated_at DATETIME(3) NOT NULL,
  request_id VARCHAR(64) NULL,
  KEY idx_alert_log_record(record_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异常血糖追加处理日志';

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_time)
SELECT '异常血糖事件',0,16,'/user-service/glucose-alerts','users/GlucoseAlertsView','C','0','0','health:alert:view','warning',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='health:alert:view');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_time)
SELECT '异常事件处理',m.menu_id,1,'#','F','0','0','health:alert:handle',NOW()
FROM sys_menu m WHERE m.perms='health:alert:view'
AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='health:alert:handle');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,target.menu_id FROM sys_role_menu rm
JOIN sys_menu source ON source.menu_id=rm.menu_id AND source.perms='health:archive:view'
CROSS JOIN sys_menu target WHERE target.perms='health:alert:view';

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r CROSS JOIN sys_menu m
WHERE (r.role_id=1 OR r.role_key IN ('admin','super_admin')) AND r.del_flag='0'
AND m.perms IN ('health:alert:view','health:alert:handle');
