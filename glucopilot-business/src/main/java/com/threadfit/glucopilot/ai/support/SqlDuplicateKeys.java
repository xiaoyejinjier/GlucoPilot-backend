package com.threadfit.glucopilot.ai.support;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 唯一键冲突（错误码 1062）识别。
 * <p>
 * 用途：{@code chat_message} 的唯一键 {@code uk_session_client_active(session_id, client_msg_id, is_deleted)}
 * 是客户端幂等的实现基础——重复提交同一个 {@code clientMsgId} 时捕获 1062 当作成功，
 * 回查已有消息返回，既不多写一行也不重复扣 token。
 * <p>
 * <b>为什么要能区分「冲突发生在哪个唯一键上」</b>：同一张表上现在有两个唯一键，冲突语义完全不同——
 * <ul>
 *   <li>{@code uk_session_client_active}：客户端重复提交 → 应当<b>当成功</b>，回查既有行返回；</li>
 *   <li>{@code uk_session_seq} / {@code uk_user_active}：并发写入撞号 → 应当<b>重算后重试</b>，
 *       或回查并发方刚插入的那一行。</li>
 * </ul>
 * 只判断"是不是 1062"会把两者混为一谈：撞号被当成重复提交去回查（查不到，把真冲突吞掉），
 * 或重复提交被当成撞号去重试（无意义重试）。{@link #isDuplicateKeyFor} 就是用来分流的。
 * <p>
 * 说明：早期方案里引用的是 {@code common.util.SqlDuplicateHelper}，该类在当前分支已不存在
 * （AI 模块因此一度编译不过）。按模块边界，这里先落在 AI 模块内部（{@code ai.support}）；
 * 若后续 {@code common} 侧恢复通用工具，可直接切换过去，调用方只需改 import。
 */
public final class SqlDuplicateKeys {

    /** MySQL ER_DUP_ENTRY */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    /** SQLState：完整性约束冲突（唯一键属于此类） */
    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";

    /**
     * 从 MySQL 消息里取键名：{@code Duplicate entry 'x' for key 'uk_session_seq'}。
     * MySQL 8 会带上表名（{@code 'chat_message.uk_session_seq'}），5.7 只有键名，两者都要认。
     */
    private static final Pattern KEY_NAME = Pattern.compile("for key '([^']+)'");

    private SqlDuplicateKeys() {
    }

    /**
     * 判断异常链中是否存在唯一键冲突。
     * 只看「唯一键」这一种情况，不把其它完整性约束异常（如非空、外键）误判为幂等命中。
     */
    public static boolean isDuplicateKey(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                    return true;
                }
                if (SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sqlException.getSQLState())
                        && sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 冲突是否发生在指定的唯一键上（比对时忽略 MySQL 8 附带的表名前缀）。
     * <p>
     * 消息里解析不出键名时返回 {@code false}：宁可让调用方把这次冲突当作"未知冲突"上报，
     * 也不要凭猜测把它分流成幂等或重试。
     */
    public static boolean isDuplicateKeyFor(Throwable throwable, String keyName) {
        String actual = duplicateKeyName(throwable);
        if (actual == null || keyName == null) {
            return false;
        }
        return actual.equals(keyName) || actual.endsWith("." + keyName);
    }

    /** 取冲突的键名（可能是 {@code 表名.键名}）；解析不到返回 {@code null}。 */
    public static String duplicateKeyName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = KEY_NAME.matcher(message);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
