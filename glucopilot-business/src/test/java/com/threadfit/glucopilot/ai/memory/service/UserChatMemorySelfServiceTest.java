package com.threadfit.glucopilot.ai.memory.service;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneModelResolver;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import com.threadfit.glucopilot.common.util.JsonUtils;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.threadfit.glucopilot.ai.memory.controller.UserChatMemoryController;
import com.threadfit.glucopilot.ai.memory.dto.UserChatMemoryDtos;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.memory.service.impl.UserChatMemoryServiceImpl;

/**
 * C 端合规自助（③ 期）：{@code /api/v1/users/me/ai-memories} 的查与删。
 * <p>
 * 这一层的价值不在"能不能查出来"，而在几条<b>看代码看不出来</b>的纪律：
 * <ol>
 *   <li><b>身份只来自会话</b>：缺 userId 就是 401，绝不用路径/入参里的 id——那是越权读别人画像的入口；</li>
 *   <li><b>不返回内部字段</b>：{@code id} / {@code sourceSessionId} / {@code isAuto} 一律不出现在出参里；</li>
 *   <li><b>不做注入上限截断</b>：{@code inject-max-items} 是省 token 用的，合规页面必须给全部记忆；</li>
 *   <li><b>读失败不许静默返回空</b>：那等于告诉用户"系统没记你任何东西"，而事实可能相反；</li>
 *   <li><b>删除的 deleted_by 不写运营 ID</b>：用户行使删除权，不是运营代删，
 *       写错了管理端的删除留痕就把用户和运营混成一体。</li>
 * </ol>
 * 另外用 H2 真库验证"只返回我的、未删的"与"删完全部后查不到、别人的不受影响"——
 * 这两件事是 {@code @TableLogic} 与显式 UPDATE 的 SQL 行为，Mock 证明不了。
 */
class UserChatMemorySelfServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    private ChatUserMemoryMapper memoryMapper;
    private AIProperties aiProperties;
    private UserChatMemoryService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(ChatUserMemoryMapper.class);
        aiProperties = new AIProperties();
        service = new UserChatMemoryServiceImpl(
                mock(ChatClient.class),
                memoryMapper,
                mock(ChatMessageMapper.class),
                mock(AiPromptTemplateService.class),
                mock(SceneModelResolver.class),
                aiProperties,
                new JsonUtils(new ObjectMapper()),
                new ChatRoundGuard(),
                Runnable::run);
        RequestContext.clear();
        RequestContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------- 查询

    @Test
    void 返回四个对用户有意义的字段() {
        doReturn(List.of(row("diet_taboo", "对花生过敏", 95, LocalDateTime.of(2026, 10, 2, 9, 30)),
                row("habit", "晚饭后散步30分钟", 80, LocalDateTime.of(2026, 10, 1, 20, 0))))
                .when(memoryMapper).selectList(any());

        List<UserChatMemoryDtos.MemoryRow> rows = service.listOwnForUser(USER_ID);

        assertEquals(2, rows.size());
        assertEquals("diet_taboo", rows.get(0).getMemoryKey());
        assertEquals("对花生过敏", rows.get(0).getMemoryText());
        assertEquals(95, rows.get(0).getConfidence());
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 30), rows.get(0).getUpdatedAt());
    }

    /**
     * {@code ai.memory.inject-max-items} 是"每轮最多注入几条"的省 token 开关，
     * 与"用户能看到全部记忆"是两件事。把它误用到合规页面上，用户就会看不到某些被系统记住的事。
     */
    @Test
    void 注入上限不影响用户看到全部记忆() {
        aiProperties.getMemory().setInjectMaxItems(1);
        doReturn(List.of(row("diet_taboo", "对花生过敏", 95, LocalDateTime.now()),
                row("habit", "晚饭后散步30分钟", 80, LocalDateTime.now())))
                .when(memoryMapper).selectList(any());

        assertEquals(2, service.listOwnForUser(USER_ID).size(), "合规页面不按注入上限截断");
    }

    /**
     * 读失败必须抛：返回空列表会被理解成"系统没记你任何东西"，而那正是用户来查这件事的原因。
     */
    @Test
    void 读失败时抛异常而不是假装没有记忆() {
        doThrow(new IllegalStateException("db down")).when(memoryMapper).selectList(any());

        assertThrows(IllegalStateException.class, () -> service.listOwnForUser(USER_ID));
    }

    @Test
    void 没有记忆时返回空列表() {
        doReturn(List.of()).when(memoryMapper).selectList(any());

        assertTrue(service.listOwnForUser(USER_ID).isEmpty());
    }

    // ---------------------------------------------------------------- 删除

    @Test
    void 删除返回真实条数() {
        doReturn(3).when(memoryMapper).logicDeleteAllByUser(USER_ID, null);

        assertEquals(3, service.deleteAllForUser(USER_ID));
    }

    @Test
    void 本来就没有记忆时删除返回0而不是报错() {
        doReturn(0).when(memoryMapper).logicDeleteAllByUser(USER_ID, null);

        assertEquals(0, service.deleteAllForUser(USER_ID), "重复点击删除应当是幂等成功");
    }

    /**
     * 删除的 {@code deleted_by} 必须是 null，不能是当前上下文里的 opsId。
     * <p>
     * 这是"用户自助删除"与"运营代删"的唯一区分点：一旦把 opsId 写进去，
     * 管理端排查"这个用户的记忆是谁删的"就会看到某个运营的名字，而实际上没人动过。
     */
    @Test
    void 用户自助删除不把运营ID写进deleted_by() {
        RequestContext.setOpsId(99L);
        doReturn(1).when(memoryMapper).logicDeleteAllByUser(USER_ID, null);

        service.deleteAllForUser(USER_ID);

        verify(memoryMapper).logicDeleteAllByUser(USER_ID, null);
    }

    // ---------------------------------------------------------------- 契约（HTTP 层）

    @Test
    void 契约查询返回records且不含内部字段() throws Exception {
        doReturn(List.of(row("diet_taboo", "对花生过敏", 95, LocalDateTime.of(2026, 10, 2, 9, 30))))
                .when(memoryMapper).selectList(any());

        MockMvcBuilders.standaloneSetup(new UserChatMemoryController(service)).build()
                .perform(get("/api/v1/users/me/ai-memories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].memoryKey").value("diet_taboo"))
                .andExpect(jsonPath("$.data.records[0].memoryText").value("对花生过敏"))
                .andExpect(jsonPath("$.data.records[0].confidence").value(95))
                .andExpect(jsonPath("$.data.records[0].updatedAt").exists())
                // 内部标识一个都不能漏：前端拿到 id 就会想做单条删除，
                // 拿到 sourceSessionId 就等于知道"那段对话在系统里还留着痕迹"
                .andExpect(jsonPath("$.data.records[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].sourceSessionId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].isAuto").doesNotExist());
    }

    @Test
    void 契约删除返回deleted条数() throws Exception {
        doReturn(2).when(memoryMapper).logicDeleteAllByUser(USER_ID, null);

        MockMvcBuilders.standaloneSetup(new UserChatMemoryController(service)).build()
                .perform(delete("/api/v1/users/me/ai-memories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deleted").value(2));
    }

    /**
     * 未登录（会话里没有 userId）必须 401，而且<b>一次库都不查</b>。
     * <p>
     * 这是本接口最要紧的一条：路径里写的是 {@code me}，一旦身份解析失败后还继续往下走，
     * {@code WHERE user_id = null} 在 MyBatis-Plus 里是"这个条件不拼进 SQL"，
     * 后果是读到、甚至删掉<b>所有人</b>的记忆。
     */
    @Test
    void 未登录时查询与删除都返回401且不碰数据库() throws Exception {
        RequestContext.clear();
        UserChatMemoryService untouched = mock(UserChatMemoryService.class);

        MockMvcBuilders.standaloneSetup(new UserChatMemoryController(untouched))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/api/v1/users/me/ai-memories"))
                .andExpect(status().isUnauthorized());

        MockMvcBuilders.standaloneSetup(new UserChatMemoryController(untouched))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(delete("/api/v1/users/me/ai-memories"))
                .andExpect(status().isUnauthorized());

        // 身份缺失时服务方法根本不该被调用（控制器先 requireUserId 抛 401）
        verifyNoInteractions(untouched);
    }

    /** 服务层自己也守住 null：不查库、不删库，返回空结果/0。 */
    @Test
    void 服务层对空userId不做任何数据库操作() {
        assertTrue(service.listOwnForUser(null).isEmpty());
        assertEquals(0, service.deleteAllForUser(null));

        verifyNoInteractions(memoryMapper);
    }

    // ---------------------------------------------------------------- 真实库（H2，MySQL 兼容模式）

    /**
     * 真库验证三件事：
     * <ol>
     *   <li>只返回<b>我的、未删的</b>（过滤由 {@code @TableLogic} 统一追加）；</li>
     *   <li>删除后自己查不到，别人的记忆一条不动；</li>
     *   <li>{@code deleted_at} 与 {@code is_deleted} 成对写入（{@code chk_cum_del_consistent}）。</li>
     * </ol>
     */
    @Test
    void 真实库上只返回我的未删除记忆且删除不影响别人() throws Exception {
        UnpooledDataSource datasource = memoryDatasource();
        createMemoryTable(datasource);
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO chat_user_memory "
                    + "(id,user_id,memory_key,memory_text,confidence,created_by,is_deleted,gmt_modified,deleted_at) VALUES "
                    + "(1,7,'diet_taboo','对花生过敏',95,0,0,TIMESTAMP '2026-10-01 10:00:00',NULL),"
                    + "(2,7,'habit','晚饭后散步30分钟',80,5,0,TIMESTAMP '2026-10-03 10:00:00',NULL),"
                    + "(3,7,'preference','早就删掉的旧记忆',70,0,1,TIMESTAMP '2026-10-02 10:00:00',"
                    + "TIMESTAMP '2026-10-02 11:00:00'),"
                    + "(4,8,'habit','别人的记忆',80,0,0,TIMESTAMP '2026-10-04 10:00:00',NULL)");
        }
        SqlSessionFactory sessionFactory = memorySqlSessionFactory(datasource);
        sessionFactory.getConfiguration().addMapper(ChatUserMemoryMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            ChatUserMemoryMapper realMapper = session.getMapper(ChatUserMemoryMapper.class);
            UserChatMemoryService realService = realServiceWith(realMapper);

            List<UserChatMemoryDtos.MemoryRow> rows = realService.listOwnForUser(USER_ID);
            assertEquals(2, rows.size(), "已删除的行与他人的记忆都不算");
            assertEquals(List.of("diet_taboo", "habit"), rows.stream()
                    .map(UserChatMemoryDtos.MemoryRow::getMemoryKey).toList(),
                    "按置信度降序：更确定的排前面");

            assertEquals(2, realService.deleteAllForUser(USER_ID));
            assertTrue(realService.listOwnForUser(USER_ID).isEmpty(), "删完之后自己查不到");

            List<UserChatMemoryDtos.MemoryRow> others = realService.listOwnForUser(OTHER_USER_ID);
            assertEquals(1, others.size(), "别人的记忆一条都不能动");

            try (var statement = session.getConnection().createStatement();
                 var result = statement.executeQuery(
                         "SELECT is_deleted, deleted_at FROM chat_user_memory WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("is_deleted"));
                assertTrue(result.getTimestamp("deleted_at") != null,
                        "deleted_at 必须与 is_deleted 成对写入，否则 CHECK 会拒绝");
            }
        }
    }

    // ---------------------------------------------------------------- 辅助

    private UserChatMemoryService realServiceWith(ChatUserMemoryMapper realMapper) {
        return new UserChatMemoryServiceImpl(
                mock(ChatClient.class),
                realMapper,
                mock(ChatMessageMapper.class),
                mock(AiPromptTemplateService.class),
                mock(SceneModelResolver.class),
                aiProperties,
                new JsonUtils(new ObjectMapper()),
                new ChatRoundGuard(),
                Runnable::run);
    }

    private static ChatUserMemoryEntity row(String key, String text, int confidence, LocalDateTime updatedAt) {
        ChatUserMemoryEntity row = new ChatUserMemoryEntity();
        row.setUserId(USER_ID);
        row.setMemoryKey(key);
        row.setMemoryText(text);
        row.setConfidence(confidence);
        row.setGmtModified(updatedAt);
        return row;
    }

    private static UnpooledDataSource memoryDatasource() {
        return new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:selfmem_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** 建表：列与约束照搬 V1 迁移（唯一键 + 删除一致性 CHECK）。 */
    private static void createMemoryTable(DataSource datasource) throws SQLException {
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE chat_user_memory ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                    + "memory_key VARCHAR(64) NOT NULL, memory_text VARCHAR(512) NOT NULL, "
                    + "source_session_id BIGINT NULL, confidence TINYINT NOT NULL DEFAULT 100, "
                    + "created_by BIGINT NOT NULL DEFAULT 0, "
                    + "gmt_create TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "updated_by BIGINT NOT NULL DEFAULT 0, "
                    + "gmt_modified TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "deleted_by BIGINT NULL, deleted_at TIMESTAMP(3) NULL, "
                    + "is_deleted TINYINT NOT NULL DEFAULT 0, "
                    + "CONSTRAINT uk_user_key_active UNIQUE (user_id, memory_key, is_deleted), "
                    + "CONSTRAINT chk_cum_del_consistent CHECK ("
                    + "(is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL) "
                    + "OR (is_deleted = 1 AND deleted_at IS NOT NULL)))");
        }
    }

    private static SqlSessionFactory memorySqlSessionFactory(DataSource datasource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
        db.setLogicDeleteField("isDeleted");
        db.setLogicDeleteValue("1");
        db.setLogicNotDeleteValue("0");
        global.setDbConfig(db);
        global.setMetaObjectHandler(new AuditMetaObjectHandler());
        factory.setGlobalConfig(global);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        factory.setPlugins(interceptor);
        return factory.getObject();
    }
}
