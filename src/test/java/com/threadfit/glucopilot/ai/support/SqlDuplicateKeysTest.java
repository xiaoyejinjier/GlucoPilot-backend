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

    // ---------- 区分「冲突发生在哪个唯一键上」----------
    // 同一张 chat_message 上现在有两个唯一键：客户端幂等键要"当成功回查"，
    // 顺序号键要"重算重试"。判错方向都会出事：前者被当成后者会无意义重试，
    // 后者被当成前者会去回查一条根本不存在的行、把真冲突吞掉。

    @Test
    void 应能从MySQL8的消息里解析出带表名的键名() {
        DuplicateKeyException exception = new DuplicateKeyException("dup",
                new SQLException("Duplicate entry '9-2' for key 'chat_message.uk_session_seq'", "23000", 1062));

        assertTrue(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_seq"));
        assertFalse(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_client_active"));
    }

    @Test
    void 也应兼容不带表名的键名写法() {
        DuplicateKeyException exception = new DuplicateKeyException("dup",
                new SQLException("Duplicate entry 'c1' for key 'uk_session_client_active'", "23000", 1062));

        assertTrue(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_client_active"));
        assertFalse(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_seq"));
    }

    @Test
    void 键名不应被前缀误匹配() {
        // 用 endsWith(".键名") 比对，不能退化成 contains：否则 uk_x_uk_session_seq 会被误判
        DuplicateKeyException exception = new DuplicateKeyException("dup",
                new SQLException("Duplicate entry 'x' for key 'chat_message.other_uk_session_seq'", "23000", 1062));

        assertFalse(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_seq"));
    }

    @Test
    void 解析不出键名时不应猜测() {
        // 宁可让调用方当"未知冲突"上报，也不要凭猜测分流成幂等或重试
        DuplicateKeyException exception = new DuplicateKeyException("dup",
                new SQLException("Duplicate entry 'x'", "23000", 1062));

        assertTrue(SqlDuplicateKeys.isDuplicateKey(exception));
        assertFalse(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_seq"));
        assertFalse(SqlDuplicateKeys.isDuplicateKeyFor(exception, "uk_session_client_active"));
    }
}
