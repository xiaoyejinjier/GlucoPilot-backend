package com.threadfit.glucopilot.common.util;

import java.sql.SQLException;

/**
 * MySQL 唯一键冲突（1062）判断，聊天 clientMsgId 幂等可复用。
 */
public final class SqlDuplicateHelper {

    private static final String MYSQL_DUPLICATE = "23000";
    private static final int MYSQL_DUPLICATE_CODE = 1062;

    private SqlDuplicateHelper() {
    }

    public static boolean isDuplicateKey(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == MYSQL_DUPLICATE_CODE
                        || MYSQL_DUPLICATE.equals(sqlException.getSQLState())) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
