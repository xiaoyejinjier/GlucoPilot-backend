package com.threadfit.glucopilot.ai.support;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一键冲突识别：幂等写入「重复 clientMsgId 当成功」这条路径的正确性完全依赖它。
 * 判错的两个方向都要防：把冲突漏判（用户重复提交会报 500）、把非冲突误判（真错误被吞掉）。
 */
class SqlDuplicateKeysTest {

    @Test
    void Spring翻译后的重复键异常应命中() {
        DuplicateKeyException exception = new DuplicateKeyException("duplicate",
                new SQLException("Duplicate entry 'c1' for key 'uk_session_client_active'", "23000", 1062));
        assertTrue(SqlDuplicateKeys.isDuplicateKey(exception));
    }

    @Test
    void 原生完整性约束异常应命中() {
        assertTrue(SqlDuplicateKeys.isDuplicateKey(
                new SQLIntegrityConstraintViolationException("Duplicate entry 'c1' for key 'uk'")));
    }

    @Test
    void 其它完整性约束冲突不应误判为重复键() {
        // 1048 = Column cannot be null，同属 SQLState 23000，但不是唯一键冲突
        assertFalse(SqlDuplicateKeys.isDuplicateKey(
                new SQLException("Column 'session_id' cannot be null", "23000", 1048)));
    }

    @Test
    void 空值与无关异常应返回false() {
        assertFalse(SqlDuplicateKeys.isDuplicateKey(null));
        assertFalse(SqlDuplicateKeys.isDuplicateKey(new IllegalStateException("boom")));
    }
}
