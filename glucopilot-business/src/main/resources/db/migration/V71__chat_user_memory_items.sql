-- V71：长期记忆从「每类一个槽位」改成「每类多个条目」
--
-- 背景：原来唯一键是 uk_user_key_active(user_id, memory_key, is_deleted)，等于把每个类当成
-- 一个填空（"忌口 = 某一句"）。实际忌口/偏好/身体状况天然是集合：不吃香菜、不吃辣、海鲜过敏
-- 是三条互不替代的事实。槽位模型的后果有三个：
--   1) 用户只能删掉整类（删香菜要连过敏一起删）；
--   2) 模型被迫把多条事实合并成一句话，抽错一次损失一整类；
--   3) 新事实会被"只升不降"的置信度规则挡住，甚至覆盖掉更高置信度的过敏信息。
--
-- 改法：加 item_hash（规范化正文的 SHA-256）参与唯一键，条目的身份是「哪条内容」而不是「哪个类」。
--   唯一键 uk_user_key_item(user_id, memory_key, item_hash, is_deleted)
--   → 同一个人、同一个类、同一句话只保留一条生效行；不同内容各占一行。
--
-- 为什么 item_hash 不含 user_id/key：唯一键里已经有这两列，哈希只负责"内容相同"，
-- 让它与用户无关反而便于排查（同一句话在不同用户下哈希一致）。
-- 为什么仍把 is_deleted 编进唯一键：MyBatis-Plus 的逻辑删除只置 is_deleted，
-- 生效行(0)与被删行(1)必须能共存；代价是「同一条目只能留一条墓碑」，
-- 因此删除接口会先物理清掉同条目的历史墓碑（见 ChatUserMemoryMapper）。

-- 1) 新增 item_hash（幂等）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_user_memory'
                   AND COLUMN_NAME = 'item_hash');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE chat_user_memory ADD COLUMN item_hash CHAR(64) NULL COMMENT ''正文规范化后的SHA-256，条目身份'' AFTER memory_text',
  'DO 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 回填现有行：存量是「每类一条」，直接当该类第 1 个条目
UPDATE chat_user_memory
   SET item_hash = SHA2(TRIM(memory_text), 256)
 WHERE item_hash IS NULL OR item_hash = '';

-- 3) 换唯一键：先摘旧的（幂等），再挂新的（幂等）
SET @has_old := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_user_memory'
                   AND INDEX_NAME = 'uk_user_key_active');
SET @ddl := IF(@has_old > 0,
  'ALTER TABLE chat_user_memory DROP INDEX uk_user_key_active',
  'DO 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_new := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_user_memory'
                   AND INDEX_NAME = 'uk_user_key_item');
SET @ddl := IF(@has_new = 0,
  'ALTER TABLE chat_user_memory ADD UNIQUE KEY uk_user_key_item (user_id, memory_key, item_hash, is_deleted)',
  'DO 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------
-- 顺带：长期记忆抽取提示词升到 v2（新条目模型要求「一条只记一件事」）
-- 为什么和新唯一键放在同一个迁移：两者是同一件事的两半——
-- 表结构允许多条目、提示词却仍要求"同一个 key 只输出最可靠的一条"，
-- 就会出现"结构能存三条、模型却只给一条"的割裂。
-- 老版本 v1 保留并停用，便于回滚（is_enabled=0 只是不再被读取）。
-- ---------------------------------------------------------------
INSERT INTO ai_prompt_template
    (scene_code, template_name, prompt_body, variables_json, model_code, version_no, is_enabled, change_note, config_version)
SELECT 'MEMORY', '长期记忆抽取（一条只记一件事）', '# 角色：长期记忆抽取器
你的输入是**用户与控糖助手之间的一段对话原文**（可能还包含"已知的长期记忆"）。你的任务是从中挑出**值得长期记住的用户事实**，输出 JSON。

## 只提取这五类（键固定，不要自创）
1. diet_taboo：忌口、过敏、明确不吃的食物（例：对花生过敏、不吃动物内脏）；
2. glucose_goal：用户自己说过的血糖目标或医生给的目标（例：想把餐后控制到 8 以内）；
3. health_context：长期健康状况与背景（例：2 型糖尿病确诊 3 年、有高血压）；
4. habit：稳定的生活/运动/记录习惯（例：晚饭后固定散步 30 分钟、习惯早上测空腹血糖）；
5. preference：稳定的沟通偏好（例：希望回答简短、不要反复提醒就医）。

## 绝对不要提取
1. 单次的数据（某天的血糖值、某顿饭的内容）——那是会话内容，不是长期记忆；
2. 助手说过的话、建议、结论；
3. 诊断、用药剂量、调药方案：**一个字都不要写进记忆**（这类内容只能留在对话里并引导就医）；
4. 推测出来的信息：用户没说过的，不要替他补全；
5. 隐私标识信息：身份证、手机号、地址、单位名称等。

## 置信度 confidence（0 到 100 的整数）
- 用户**明确陈述**（"我不吃花生""医生让我控到 8 以内"）：90~100；
- 用户**间接表达但很清楚**（"我一直都是晚上散步"）：70~89；
- 需要推断、或只提过一次的偏好：60~69；
- 拿不准的一律**不要输出**这一条，而不是给低分硬塞。

## 输出格式
只输出一个 JSON 对象，不要 Markdown 代码块围栏、不要任何解释文字。
对象只有一个字段 memories（数组），数组每一项包含四个字段：

1. key：上面五类之一（diet_taboo / glucose_goal / health_context / habit / preference）；
2. text：这条事实（不超过 60 字，陈述句，主语是用户，例："对花生过敏"而不是"用户说他对花生过敏"）；
3. confidence：0 到 100 的整数；
4. evidence：用户的原话片段（用于人工核对，不超过 40 字）。

示例（示意字段与取值，**不要照抄内容**）：key 取 diet_taboo、text 取"对花生过敏"、confidence 取 95、
evidence 取"吃了花生会起疹子"，即表示记下一条"对花生过敏"的忌口。

- 没有可提取内容时输出一个空数组的 memories 字段（即该字段为空列表）；
- 同一条事实只输出一次；但**同一个 key 可以输出多条**（每条只写一件事）：「不吃香菜」与「海鲜过敏」都属于 diet_taboo，要拆成两条，不要合并成一句长话；
- 已有记忆里已经有的同一条事实不要再输出；如果用户更正了某个说法，输出更正后的那条（而不是把新旧两句都写出来）；
- 不要输出多余符号，不要用 Markdown，也不要输出上面示例之外的解释。
', '[]', NULL, 2, 1,
       'V71：同键可多条，一条只写一件事，已更正的说法覆盖旧说法', 1
 WHERE NOT EXISTS (
    SELECT 1 FROM ai_prompt_template
     WHERE BINARY scene_code = 'MEMORY' AND version_no = 2 AND is_deleted = 0);

UPDATE ai_prompt_template
   SET is_enabled = 0
 WHERE BINARY scene_code = 'MEMORY' AND version_no <> 2 AND is_deleted = 0 AND is_enabled = 1;
