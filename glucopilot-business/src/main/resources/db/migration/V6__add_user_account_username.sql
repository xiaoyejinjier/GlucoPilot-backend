-- 为现有 C 端账号增加用户名。
-- 这是独立的增量升级脚本：已有数据库只执行 ALTER/UPDATE，不需要重建表。

ALTER TABLE user_account
  ADD COLUMN username VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户名（与用户资料昵称同步）' AFTER id;

-- 优先使用已经填写的昵称；历史空昵称账号生成稳定的默认用户名。
UPDATE user_account ua
LEFT JOIN user_profile up
  ON up.user_id = ua.id
 AND up.is_deleted = 0
SET ua.username = COALESCE(NULLIF(TRIM(up.nickname), ''), CONCAT('用户_', ua.id))
WHERE ua.username = '';

-- 回填完成后取消空串默认值，要求后续创建账号时显式写入用户名。
ALTER TABLE user_account
  MODIFY COLUMN username VARCHAR(64) NOT NULL COMMENT '用户名（与用户资料昵称同步）';
