package com.threadfit.glucopilot.ai.business.chat.service;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatContextEntity;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper;
import com.threadfit.glucopilot.ai.memory.assembly.ChatContextShard;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.threadfit.glucopilot.ai.business.chat.service.impl.ChatContextRecorderImpl;

/**
 * 分片元数据落库（③ 期）的语义。
 *
 * <h2>这一层最要紧的一条：两个内容列恒为 NULL</h2>
 * {@code chat_context} 有 {@code content_text} / {@code content_json}，且 V1 曾用
 * {@code chk_cc_payload} 要求"二者至少有一个非空"。③ 期的决定是只记元数据（理由见
 * {@code ChatContextShard}），V26 因此删掉了那条 CHECK。下面用一个<b>保留该 CHECK 的 H2 表</b>
 * 反过来锁住这件事的一半，再用一个删掉 CHECK 的表验证"真的写得进去"——
 * 两半合起来说明：我们确实是靠迁移删约束、而不是靠偷偷往内容列塞东西绕过去的。
 *
 * <h2>其它几条</h2>
 * <ul>
 *   <li>{@code assistant_msg_id} 必须是落库后回填的那个 ID（不是用户消息 ID）；</li>
 *   <li>轮次号由助手消息的 {@code seq_no} 推导，不查库；</li>
 *   <li>写入失败只 WARN：它是旁路观测，不能让一轮已经成功的回复变成错误。</li>
 * </ul>
 */
class ChatContextRecorderTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 48L;
    private static final Long TRIGGER_MSG_ID = 101L;
    private static final Long ASSISTANT_MSG_ID = 102L;

    private static List<ChatContextShard> shards() {
        return List.of(
                ChatContextShard.systemPrompt(1200),
                ChatContextShard.longTermMemory(30),
                ChatContextShard.summary(120),
                ChatContextShard.toolDigest(40),
                ChatContextShard.historyMessages(800));
    }

    // ---------------------------------------------------------------- 字段映射

    @Test
    void 每条分片落一行且两个内容列为空() {
        ChatContextMapper mapper = mock(ChatContextMapper.class);

        new ChatContextRecorderImpl(mapper).record(USER_ID, SESSION_ID, TRIGGER_MSG_ID, ASSISTANT_MSG_ID,
                "deepseek-v4-pro", 3, shards());

        ArgumentCaptor<ChatContextEntity> captor = ArgumentCaptor.forClass(ChatContextEntity.class);
        verify(mapper, times(5)).insert(captor.capture());
        List<ChatContextEntity> rows = captor.getAllValues();
        for (ChatContextEntity row : rows) {
            assertNull(row.getContentText(), "content_text 恒为 NULL：存正文会让用户的删除权失效");
            assertNull(row.getContentJson(), "content_json 恒为 NULL（同上）");
            assertEquals(SESSION_ID, row.getSessionId());
            assertEquals(USER_ID, row.getUserId());
            assertEquals(TRIGGER_MSG_ID, row.getTriggerMsgId());
            assertEquals(ASSISTANT_MSG_ID, row.getAssistantMsgId(), "assistant_msg_id 必须是落库后的助手消息 ID");
            assertEquals(3, row.getContextRound());
            assertEquals("deepseek-v4-pro", row.getModelCode());
            assertEquals(USER_ID, row.getCreatedBy(), "回调线程上取不到 RequestContext，created_by 显式写 userId");
        }
        assertEquals(List.of(1, 6, 3, 5, 6), rows.stream().map(ChatContextEntity::getContextType).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), rows.stream().map(ChatContextEntity::getSortNo).toList());
        assertEquals(1200, rows.get(0).getTokenEstimate());
    }

    @Test
    void 轮次号由助手消息的顺序号推导() {
        assertEquals(1, ChatContextRecorder.roundOf(null));
        assertEquals(1, ChatContextRecorder.roundOf(0));
        assertEquals(1, ChatContextRecorder.roundOf(2), "第 1 轮助手消息的 seq_no 是 2");
        assertEquals(2, ChatContextRecorder.roundOf(4));
        assertEquals(3, ChatContextRecorder.roundOf(6));
    }

    @Test
    void 空分片时一次库都不写() {
        ChatContextMapper mapper = mock(ChatContextMapper.class);

        new ChatContextRecorderImpl(mapper).record(USER_ID, SESSION_ID, TRIGGER_MSG_ID, ASSISTANT_MSG_ID,
                "m", 1, List.of());

        verify(mapper, never()).insert(any(ChatContextEntity.class));
    }

    /** 缺身份（无状态调用）时直接跳过：没有 user/session 的行没有任何分析价值。 */
    @Test
    void 缺用户或会话时跳过写入() {
        ChatContextMapper mapper = mock(ChatContextMapper.class);
        ChatContextRecorder recorder = new ChatContextRecorderImpl(mapper);

        recorder.record(null, SESSION_ID, TRIGGER_MSG_ID, ASSISTANT_MSG_ID, "m", 1, shards());
        recorder.record(USER_ID, null, TRIGGER_MSG_ID, ASSISTANT_MSG_ID, "m", 1, shards());

        verify(mapper, never()).insert(any(ChatContextEntity.class));
    }

    /**
     * 写入失败只记日志：这一轮回复已经成功发出去了，不能因为"观测"失败把它变成错误。
     * 与 {@code OpsAuditRecorder} / {@code ToolCallTraceRecorder} 同一条纪律。
     */
    @Test
    void 写入失败只记日志不向上抛() {
        ChatContextMapper mapper = mock(ChatContextMapper.class);
        doThrow(new IllegalStateException("chat_context 表不可用"))
                .when(mapper).insert(any(ChatContextEntity.class));

        assertDoesNotThrow(() -> new ChatContextRecorderImpl(mapper)
                .record(USER_ID, SESSION_ID, TRIGGER_MSG_ID, ASSISTANT_MSG_ID, "m", 1, shards()));

        verify(mapper).insert(any(ChatContextEntity.class));
    }

    // ---------------------------------------------------------------- 真库（H2，MySQL 兼容模式）

    /**
     * 迁移后的表（无 {@code chk_cc_payload}）真的能写进"两个内容列都为 NULL"的行，
     * 且 {@code chk_cc_type} 允许我们用到的 1/3/5/6。
     * <p>
     * <b>为什么建表用 {@code session.getConnection()} 而不是另开一条连接</b>：H2 2.x 的 mem 库里，
     * 建表连接关闭后 {@code CHECK (context_type IN (1,2,3,4,5,6))} 缓存的表达式会指向已关闭的
     * Database 对象，之后<b>合法的插入会被误报成 23514「Check constraint invalid … The database has
     * been closed」</b>（本次实测：同一连接内插入正常，换一条连接必失败；此前的 admin 记忆用例没踩到，
     * 因为它的 CHECK 是 AND/OR 比较而不是 IN 列表）。
     * 把 DDL 与插入放在同一个会话的连接上，就完全绕开了这个生命周期问题。
     * 被测代码没有问题——生产是 MySQL，没有这套连接生命周期。
     */
    @Test
    void 真库上只写元数据的行可以落库() throws Exception {
        UnpooledDataSource datasource = contextDatasource();
        SqlSessionFactory sessionFactory = contextSqlSessionFactory(datasource);
        sessionFactory.getConfiguration().addMapper(ChatContextMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            createContextTable(session.getConnection(), false);
            ChatContextMapper mapper = session.getMapper(ChatContextMapper.class);

            new ChatContextRecorderImpl(mapper).record(USER_ID, SESSION_ID, TRIGGER_MSG_ID, ASSISTANT_MSG_ID,
                    "deepseek-v4-pro", 2, shards());

            List<ChatContextEntity> rows = mapper.selectList(new LambdaQueryWrapper<ChatContextEntity>()
                    .eq(ChatContextEntity::getSessionId, SESSION_ID)
                    .orderByAsc(ChatContextEntity::getSortNo));
            assertEquals(5, rows.size());
            assertTrue(rows.stream().allMatch(row -> row.getContentText() == null && row.getContentJson() == null));
            assertEquals(ASSISTANT_MSG_ID, rows.get(0).getAssistantMsgId());
            assertEquals(2, rows.get(0).getContextRound());
            assertEquals("system_prompt", rows.get(0).getContextKey());
            assertEquals("history_messages", rows.get(4).getContextKey());
        }
    }

    /**
     * 反向验证：保留旧 CHECK 时，只写元数据的行会被数据库拒绝。
     * <p>
     * 作用是把"为什么必须有 V26"变成可执行的事实——否则将来有人看到 V26 只是删了一条约束，
     * 很容易以为它在删验证、顺手加回去（然后线上第一批分片全都写不进去，而 Recorder 只 WARN，
     * 表现成"chat_context 一直是空的"，没有任何报错）。
     */
    @Test
    void 保留旧CHECK时只写元数据的行会被拒绝() throws Exception {
        UnpooledDataSource datasource = contextDatasource();
        SqlSessionFactory sessionFactory = contextSqlSessionFactory(datasource);
        sessionFactory.getConfiguration().addMapper(ChatContextMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            createContextTable(session.getConnection(), true);
            ChatContextMapper mapper = session.getMapper(ChatContextMapper.class);

            // Recorder 吞异常，所以这里不会抛：只能靠"一行都没写进去"暴露失败
            new ChatContextRecorderImpl(mapper).record(USER_ID, SESSION_ID, TRIGGER_MSG_ID, ASSISTANT_MSG_ID,
                    "m", 1, shards());

            assertEquals(0, mapper.selectCount(new LambdaQueryWrapper<ChatContextEntity>()
                    .eq(ChatContextEntity::getSessionId, SESSION_ID)),
                    "旧的 chk_cc_payload 会拒绝两个内容列都为 NULL 的行——这正是 V26 要删掉它的原因");
        }
    }

    // ---------------------------------------------------------------- 辅助

    private static UnpooledDataSource contextDatasource() {
        return new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:chatctx_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /**
     * 建表：列与 V1 迁移的 chat_context 对齐。
     *
     * @param connection        执行 DDL 的连接（<b>必须</b>与插入用的是同一个会话，原因见真库用例注释）
     * @param withPayloadCheck  true = 保留 V1 的 {@code chk_cc_payload}（模拟迁移前），
     *                          false = 迁移后的形态
     */
    private static void createContextTable(java.sql.Connection connection, boolean withPayloadCheck) throws Exception {
        String payload = withPayloadCheck
                ? ", CONSTRAINT chk_cc_payload CHECK (content_text IS NOT NULL OR content_json IS NOT NULL)"
                : "";
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE chat_context ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "trigger_msg_id BIGINT NULL, assistant_msg_id BIGINT NULL, "
                    + "context_round INT NOT NULL DEFAULT 1, context_type TINYINT NOT NULL, "
                    + "context_key VARCHAR(64) NULL, content_text CLOB NULL, content_json CLOB NULL, "
                    + "token_estimate INT NULL, model_code VARCHAR(64) NULL, sort_no INT NOT NULL DEFAULT 0, "
                    + "created_by BIGINT NOT NULL DEFAULT 0, "
                    + "gmt_create TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "updated_by BIGINT NOT NULL DEFAULT 0, "
                    + "gmt_modified TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "deleted_by BIGINT NULL, deleted_at TIMESTAMP(3) NULL, is_deleted TINYINT NOT NULL DEFAULT 0, "
                    + "CONSTRAINT chk_cc_type CHECK (context_type IN (1, 2, 3, 4, 5, 6))"
                    + payload + ")");
        }
    }

    private static SqlSessionFactory contextSqlSessionFactory(DataSource datasource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
        db.setLogicDeleteField("isDeleted");
        global.setDbConfig(db);
        global.setMetaObjectHandler(new AuditMetaObjectHandler());
        factory.setGlobalConfig(global);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        factory.setPlugins(interceptor);
        return factory.getObject();
    }
}
