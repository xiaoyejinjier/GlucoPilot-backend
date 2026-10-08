package com.threadfit.glucopilot.ai.support;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * MySQL 唯一键冲突（错误码 1062）识别。
 * <p>
 * 用途：{@code chat_message} 的唯一键 {@code uk_session_client_active(session_id, client_msg_id, is_deleted)}
 * 是客户端幂等的实现基础——重复提交同一个 {@code clientMsgId} 时捕获 1062 当作成功，
 * 回查已有消息返回，既不多写一行也不重复扣 token。
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
}
