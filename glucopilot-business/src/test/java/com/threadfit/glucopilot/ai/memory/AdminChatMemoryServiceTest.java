package com.threadfit.glucopilot.ai.memory;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.common.audit.OpsAuditRecorder;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端长期记忆（契约 4.14 / 4.15）的语义。
 * <p>
 * 守四件事，前三件在 Mock 层钉死，第四件必须落到真实 SQL 上：
 * <ol>
 *   <li><b>只读未删除</b>：过滤由 {@code @TableLogic} 统一追加，业务 SQL 不再手写一份——
 *       两份条件漂移时会出现"删了却还查得到"这种最难查的错；</li>
 *   <li><b>分页夹紧</b>：pageSize 缺省 20、上限 100，越界夹紧而不是 400（与接口 5.3 同口径）；</li>
 *   <li><b>审计不含正文</b>：查询与删除各写一条，detail_json 只有 {@code {"count":N}}，
 *       不能把 memory_text 抄进 ops_audit_log——否则用户删除记忆之后，正文仍留在审计表里；</li>
 *   <li><b>审计失败不影响业务</b>：把 Recorder 桩成抛异常，两个方法仍必须成功。</li>
 * </ol>
 * 另外用 H2 真库（MySQL 兼容模式）跑一遍
 * {@link #真实库上只查未删除且批量删除后同键可以再写()}：「只查未删除」「一条语句批量逻辑删除」
 * 「同 key 删后可以再写」都是 SQL 层行为，Mock 只能证明"调用发生了"，证明不了"数据库真的这么做了"。
 * <p>
 * 最后两个用例（{@code 契约4_14…} / {@code 契约4_15…}）钉住 HTTP 契约本身：路径、权限串与出参形状——
 * 这类东西拼错了能编译能启动，只在运营点进去时才变成 403 或空页。
 */
class AdminChatMemoryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OPS_ID = 99L;

    /** 正文用一段独特文本：审计断言靠它证明"正文没有被抄进 detail_json"。 */
    private static final String MEMORY_TEXT = "对花生过敏（这条正文不允许出现在审计里）";

    private ChatUserMemoryMapper memoryMapper;
    private OpsAuditRecorder auditRecorder;
    private AdminChatMemoryService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(ChatUserMemoryMapper.class);
        auditRecorder = mock(OpsAuditRecorder.class);
        service = new AdminChatMemoryService(memoryMapper, auditRecorder);
        RequestContext.clear();
        RequestContext.setOpsId(OPS_ID);
        RequestContext.setClientIp("10.0.0.9");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------- 4.14 查询

    @Test
    void 列表按字段映射并给出是否自动抽取() {
        ChatUserMemoryEntity auto = row(11L, "diet_taboo", MEMORY_TEXT, 95, 0L);
        auto.setSourceSessionId(88L);
        auto.setGmtModified(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        ChatUserMemoryEntity manual = row(12L, "habit", "晚饭后散步30分钟", 80, 5L);
        doReturn(page(List.of(auto, manual), 2L)).when(memoryMapper).selectPage(any(), any());

        AdminChatMemoryDtos.MemoryList list = service.list(USER_ID, 1, 20);

        assertEquals(2L, list.getTotal().longValue());
        AdminChatMemoryDtos.MemoryRow first = list.getRecords().get(0);
        assertEquals(11L, first.getId());
        assertEquals("diet_taboo", first.getMemoryKey());
        assertEquals(MEMORY_TEXT, first.getMemoryText());
        assertEquals(95, first.getConfidence());
        assertEquals(88L, first.getSourceSessionId());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), first.getUpdatedAt());
        assertTrue(first.getIsAuto(), "created_by = 0 视为自动抽取");
        assertFalse(list.getRecords().get(1).getIsAuto(), "created_by 非 0 视为人工/用户写入");
    }

    /** created_by 为 NULL 的行（历史数据）按自动抽取展示，不能抛 NPE。 */
    @Test
    void createdBy为空时按自动抽取展示() {
        ChatUserMemoryEntity row = row(11L, "habit", "晚饭后散步30分钟", 80, null);
        doReturn(page(List.of(row), 1L)).when(memoryMapper).selectPage(any(), any());

        assertTrue(service.list(USER_ID, 1, 20).getRecords().getFirst().getIsAuto());
    }

    @Test
    void 分页参数越界被夹紧而不是报错() {
        doReturn(page(List.of(), 0L)).when(memoryMapper).selectPage(any(), any());

        service.list(USER_ID, null, null);
        assertPageRequest(1, 20);
        service.list(USER_ID, 0, -5);
        assertPageRequest(1, 20);
        service.list(USER_ID, 3, 500);
        assertPageRequest(3, 100);
    }

    @Test
    void 未删除过滤由_TableLogic_统一追加() throws Exception {
        Field isDeleted = fieldOf(ChatUserMemoryEntity.class, "isDeleted");
        assertNotNull(isDeleted.getAnnotation(TableLogic.class),
                "is_deleted 必须带 @TableLogic：框架据此自动追加 is_deleted = 0");
        warmUpLambdaCache();

        doReturn(page(List.of(), 0L)).when(memoryMapper).selectPage(any(), any());
        service.list(USER_ID, 1, 20);

        String segment = captureWrapper().getSqlSegment().replace(" ", "");
        assertTrue(segment.contains("user_id"), "查询必须限定归属用户");
        assertFalse(segment.contains("is_deleted"),
                "业务 SQL 不得再手写 is_deleted 条件：与 @TableLogic 重复后一旦漂移就会出现「删了却还查得到」");
        assertTrue(segment.contains("ORDERBYgmt_modifiedDESC,idDESC"),
                "按最近更正优先排序，id 兜底保证同一时间戳下分页稳定");
    }

    @Test
    void 用户没有记忆时返回空列表而不是报错() {
        doReturn(page(List.of(), 0L)).when(memoryMapper).selectPage(any(), any());

        AdminChatMemoryDtos.MemoryList list = service.list(USER_ID, 1, 20);

        assertTrue(list.getRecords().isEmpty());
        assertEquals(0L, list.getTotal().longValue());
    }

    // ---------------------------------------------------------------- 4.15 删除

    @Test
    void 删除返回真实条数并只发一条批量逻辑删除语句() {
        when(memoryMapper.logicDeleteAllByUser(USER_ID, OPS_ID)).thenReturn(2);

        AdminChatMemoryDtos.DeleteResult result = service.deleteAll(USER_ID);

        assertEquals(2, result.getDeleted());
        verify(memoryMapper).logicDeleteAllByUser(USER_ID, OPS_ID);
        // 逐条 updateById 是"删全部"最顺手的写法，也是长事务与半删除状态的来源，钉死不用
        verify(memoryMapper, never()).updateById(any(ChatUserMemoryEntity.class));
    }

    @Test
    void 用户没有记忆时删除返回0且不报错() {
        when(memoryMapper.logicDeleteAllByUser(USER_ID, OPS_ID)).thenReturn(0);

        assertEquals(0, service.deleteAll(USER_ID).getDeleted());
        // 没删到东西也要留痕：行使删除权这件事本身就是审计事实
        verify(auditRecorder).record("ai", "ai_memory_delete", "删除用户AI长期记忆", "user", USER_ID,
                "{\"count\":0}");
    }

    // ---------------------------------------------------------------- 审计

    @Test
    void 查询与删除各写一条审计且详情不含记忆正文() {
        doReturn(page(List.of(row(11L, "diet_taboo", MEMORY_TEXT, 95, 0L)), 1L))
                .when(memoryMapper).selectPage(any(), any());
        when(memoryMapper.logicDeleteAllByUser(USER_ID, OPS_ID)).thenReturn(1);

        service.list(USER_ID, 1, 20);
        service.deleteAll(USER_ID);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(eq("ai"), eq("ai_memory_view"), eq("查询用户AI长期记忆"),
                eq("user"), eq(USER_ID), detail.capture());
        verify(auditRecorder).record(eq("ai"), eq("ai_memory_delete"), eq("删除用户AI长期记忆"),
                eq("user"), eq(USER_ID), detail.capture());

        List<String> details = detail.getAllValues();
        assertEquals(2, details.size());
        for (String json : details) {
            assertFalse(json.contains(MEMORY_TEXT), "审计详情不能含记忆正文（审计表不该是健康内容的第二份副本）");
            assertFalse(json.contains("diet_taboo"), "键名同样不进审计：合规只关心条数");
            assertEquals("{\"count\":1}", json);
        }
    }

    /**
     * 审计是旁路：把 Recorder 桩成会抛异常，业务方法仍必须成功。
     * <p>
     * 这条不是"防 Recorder 写错"（它自己已经吞异常），而是防<b>换实现</b>：一旦有人给 Recorder
     * 加装饰器、或改成先做一次外部上报，异常就会顺着调用点冒上去，表现成"点了删除没反应，
     * 也不知道到底删没删"。约定写进测试，才不依赖"下一个人记得"。
     */
    @Test
    void 审计写入抛异常时业务方法仍然成功() {
        doThrow(new IllegalStateException("audit down")).when(auditRecorder)
                .record(anyString(), anyString(), anyString(), anyString(), any(), anyString());
        doReturn(page(List.of(row(11L, "habit", "晚饭后散步30分钟", 80, 0L)), 1L))
                .when(memoryMapper).selectPage(any(), any());
        when(memoryMapper.logicDeleteAllByUser(USER_ID, OPS_ID)).thenReturn(3);

        AdminChatMemoryDtos.MemoryList list = assertDoesNotThrow(() -> service.list(USER_ID, 1, 20));
        assertEquals(1, list.getRecords().size());
        AdminChatMemoryDtos.DeleteResult deleted = assertDoesNotThrow(() -> service.deleteAll(USER_ID));
        assertEquals(3, deleted.getDeleted());
    }

    // ---------------------------------------------------------------- 真实库（H2，MySQL 兼容模式）

    /**
     * 真库验证三件事（都是 SQL 层行为，Mock 证明不了）：
     * <ol>
     *   <li>列表只返回未逻辑删除、且属于该用户的行；</li>
     *   <li>批量逻辑删除是<b>一条</b> UPDATE，并把 is_deleted/deleted_at/deleted_by 三列一起写
     *       （漏 deleted_at 会被 DDL 的 {@code chk_cum_del_consistent} 拒绝）；</li>
     *   <li>删掉某 key 之后可以再写同 key 的新记忆——唯一键含 is_deleted，
     *       这正是"必须逻辑删除、不能物理删除"的另一半理由。</li>
     * </ol>
     */
    @Test
    void 真实库上只查未删除且批量删除后同键可以再写() throws Exception {
        UnpooledDataSource datasource = memoryDatasource();
        createMemoryTable(datasource);
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            // 明确写不同的 gmt_modified：排序断言才有确定结果（否则同一 INSERT 里时间戳相同，只能靠 id 兜底）
            sql.execute("INSERT INTO chat_user_memory "
                    + "(id,user_id,memory_key,memory_text,confidence,created_by,is_deleted,gmt_modified,deleted_at) VALUES "
                    + "(1,7,'diet_taboo','对花生过敏',95,0,0,TIMESTAMP '2026-01-01 10:00:00',NULL),"
                    + "(2,7,'habit','晚饭后散步30分钟',80,5,0,TIMESTAMP '2026-01-03 10:00:00',NULL),"
                    + "(3,7,'preference','早已删掉的旧记忆',70,0,1,TIMESTAMP '2026-01-02 10:00:00',"
                    + "TIMESTAMP '2026-01-02 11:00:00'),"
                    + "(4,8,'habit','别人的记忆',80,0,0,TIMESTAMP '2026-01-04 10:00:00',NULL)");
        }
        SqlSessionFactory sessionFactory = memorySqlSessionFactory(datasource);
        // 与生产的 @MapperScan 等价：mapper 必须先注册进配置，MP 才会注入 CRUD 并建立 TableInfo/逻辑删除
        sessionFactory.getConfiguration().addMapper(ChatUserMemoryMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            ChatUserMemoryMapper realMapper = session.getMapper(ChatUserMemoryMapper.class);
            AdminChatMemoryService realService = new AdminChatMemoryService(realMapper, auditRecorder);

            AdminChatMemoryDtos.MemoryList list = realService.list(USER_ID, 1, 20);
            assertEquals(2L, list.getTotal().longValue(), "已删除的行与他人的记忆都不算");
            assertEquals(List.of("habit", "diet_taboo"), list.getRecords().stream()
                    .map(AdminChatMemoryDtos.MemoryRow::getMemoryKey).toList());
            assertFalse(list.getRecords().get(0).getIsAuto(), "created_by = 5 的行是人工写入");

            AdminChatMemoryDtos.DeleteResult deleted = realService.deleteAll(USER_ID);
            assertEquals(2, deleted.getDeleted());
            assertEquals(0L, realService.list(USER_ID, 1, 20).getTotal().longValue());
            assertEquals(1L, countOf(realMapper, 8L), "别人的记忆不受影响");

            try (var statement = session.getConnection().createStatement();
                 var result = statement.executeQuery(
                         "SELECT is_deleted, deleted_at, deleted_by FROM chat_user_memory WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("is_deleted"));
                assertNotNull(result.getTimestamp("deleted_at"),
                        "deleted_at 必须与 is_deleted 成对写入，否则 DDL 的 chk_cum_del_consistent 会拒绝");
                assertEquals(OPS_ID.longValue(), result.getLong("deleted_by"));
            }

            ChatUserMemoryEntity again = new ChatUserMemoryEntity();
            again.setUserId(USER_ID);
            again.setMemoryKey("diet_taboo");
            again.setMemoryText("现在不过敏了");
            again.setConfidence(90);
            again.setIsDeleted(0);
            realMapper.insert(again);

            AdminChatMemoryDtos.MemoryList after = realService.list(USER_ID, 1, 20);
            assertEquals(1L, after.getTotal().longValue(), "唯一键含 is_deleted：同一把键删掉之后可以再写新的");
            assertEquals("现在不过敏了", after.getRecords().getFirst().getMemoryText());
        }
    }

    /**
     * 反向验证：{@link ChatUserMemoryMapper#logicDeleteAllByUser} 为什么必须自写 SQL。
     * <p>
     * MyBatis-Plus 内置的逻辑删除<b>只置 {@code is_deleted = 1}</b>，而本表的
     * {@code chk_cum_del_consistent} 要求删除态必须带 {@code deleted_at}——于是最顺手的那条路
     * （逐条 {@code updateById}，或直接用框架的 {@code delete(wrapper)}）在这里会被数据库拒绝。
     * 把这条钉进测试，是为了防止将来有人把批量删除"简化"成框架逻辑删除之后一路绿灯到生产
     * （单测里没有 CHECK 约束的表看不出问题，线上第一条删除就报错）。
     */
    @Test
    void 框架内置逻辑删除会被删除一致性CHECK拒绝() throws Exception {
        UnpooledDataSource datasource = memoryDatasource();
        createMemoryTable(datasource);
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO chat_user_memory "
                    + "(id,user_id,memory_key,memory_text,confidence,created_by,is_deleted) VALUES "
                    + "(1,7,'diet_taboo','对花生过敏',95,0,0)");
        }
        SqlSessionFactory sessionFactory = memorySqlSessionFactory(datasource);
        sessionFactory.getConfiguration().addMapper(ChatUserMemoryMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            ChatUserMemoryMapper realMapper = session.getMapper(ChatUserMemoryMapper.class);

            PersistenceException failure = assertThrows(PersistenceException.class, () -> realMapper.delete(
                    new LambdaUpdateWrapper<ChatUserMemoryEntity>()
                            .eq(ChatUserMemoryEntity::getUserId, USER_ID)
                            .eq(ChatUserMemoryEntity::getIsDeleted, 0)));
            // 只断言"抛了异常"是不够的：BindingException 之类也是 PersistenceException，
            // 那样这条用例会在"方法根本没被注入"时假通过。必须钉住原因是那条 CHECK
            //（H2 把约束名报成大写，所以比较时统一小写）。
            String chain = causeChain(failure).toLowerCase();
            assertTrue(chain.contains("chk_cum_del_consistent"),
                    "必须是被删除一致性 CHECK 拒绝，而不是别的错误：" + causeChain(failure));
            assertEquals(1L, countOf(realMapper, USER_ID), "语句被 CHECK 拒绝，行必须原样还在");
        }
    }

    // ---------------------------------------------------------------- 契约（HTTP 层）
    /**
     * 两个接口的路径、权限串与出参形状。
     * <p>
     * standalone MockMvc 不装配安全链，所以这里断言的是"路由与出参契约"，权限点用注解上的字符串钉住：
     * 拼错成 {@code ai:memory:read} 这类问题不会在编译期暴露，只会在线上变成"运营点进去 403"
     * （或更糟：某个宽权限角色也能删）。幂等键、限流等横切能力不在本层。
     */
    @Test
    void 契约4_14可访问且records与total都在data里() throws Exception {
        doReturn(page(List.of(row(11L, "diet_taboo", MEMORY_TEXT, 95, 0L)), 1L))
                .when(memoryMapper).selectPage(any(), any());

        MockMvcBuilders.standaloneSetup(new AdminChatMemoryController(service)).build()
                .perform(get("/admin/v1/users/{id}/chat-memories", USER_ID)
                        .param("pageNum", "1").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].memoryKey").value("diet_taboo"))
                .andExpect(jsonPath("$.data.records[0].memoryText").value(MEMORY_TEXT))
                .andExpect(jsonPath("$.data.records[0].isAuto").value(true))
                .andExpect(jsonPath("$.data.total").value(1));

        assertEquals("@ss.hasPermi('ai:memory:view')", permissionOf("list", Long.class, Integer.class, Integer.class));
    }

    @Test
    void 契约4_15删除后data为null() throws Exception {
        when(memoryMapper.logicDeleteAllByUser(USER_ID, OPS_ID)).thenReturn(2);

        MockMvcBuilders.standaloneSetup(new AdminChatMemoryController(service)).build()
                .perform(delete("/admin/v1/users/{id}/chat-memories", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", nullValue()));

        verify(memoryMapper).logicDeleteAllByUser(USER_ID, OPS_ID);
        assertEquals("@ss.hasPermi('ai:memory:manage')",
                permissionOf("deleteAll", Long.class));
    }

    // ---------------------------------------------------------------- 辅助

    private static String permissionOf(String name, Class<?>... parameterTypes) throws Exception {
        return AdminChatMemoryController.class.getMethod(name, parameterTypes)
                .getAnnotation(PreAuthorize.class).value();
    }

    private static long countOf(ChatUserMemoryMapper mapper, Long userId) {
        return mapper.selectCount(new LambdaQueryWrapper<ChatUserMemoryEntity>()
                .eq(ChatUserMemoryEntity::getUserId, userId));
    }

    /** 异常原因链拼成一段文本：断言"为什么失败"而不是"失败了"。 */
    private static String causeChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append(" | ");
        }
        return text.toString();
    }

    private static UnpooledDataSource memoryDatasource() {
        return new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:memory_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /**
     * 建表：字段与约束照搬 V1 迁移（唯一键 {@code uk_user_key_active} + 删除一致性 CHECK）。
     * <p>
     * CHECK 一定要带上——真库里它才是"逻辑删除必须同时写 deleted_at"的执行者；
     * 少了它，H2 上的测试会给"只置 is_deleted"这类实现一路绿灯。
     */
    private static void createMemoryTable(DataSource datasource) throws Exception {
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

    /**
     * 手工注册 TableInfo，只为让 {@code LambdaQueryWrapper.getSqlSegment()} 能解析列名。
     * <p>
     * 这个 lambda 缓存平时由 MP 解析 mapper 时建立（生产环境 @MapperScan 走那条路）；
     * 纯 Mock 的用例没有 MP 运行时，所以显式预热一次——否则 {@code getSqlSegment()} 会抛
     * "can not find lambda cache"，把一次断言变成一次假失败。
     */
    private static void warmUpLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig global = new GlobalConfig();
        GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
        db.setLogicDeleteField("isDeleted");
        global.setDbConfig(db);
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ChatUserMemoryEntity.class);
    }

    /** 真实 MP 会话：逻辑删除由 {@code @TableLogic} + 全局配置生效，分页需要分页插件。 */
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
        // 与生产同一个填充器：created_by/gmt_create 这些 fill 字段在 insert 语句里是**无条件**带上的，
        // 没有 MetaObjectHandler 就会以 NULL 落库，被 NOT NULL 拒绝（这类失败在裸会话里很难一眼看出来）
        global.setMetaObjectHandler(new AuditMetaObjectHandler());
        factory.setGlobalConfig(global);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        factory.setPlugins(interceptor);
        return factory.getObject();
    }

    private static ChatUserMemoryEntity row(Long id, String key, String text, int confidence, Long createdBy) {
        ChatUserMemoryEntity row = new ChatUserMemoryEntity();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setMemoryKey(key);
        row.setMemoryText(text);
        row.setConfidence(confidence);
        row.setCreatedBy(createdBy);
        return row;
    }

    private static Page<ChatUserMemoryEntity> page(List<ChatUserMemoryEntity> records, long total) {
        Page<ChatUserMemoryEntity> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    /** 断言最近一次列表查询实际用的分页参数（夹紧逻辑的最终结果）。 */
    private void assertPageRequest(long expectedPage, long expectedSize) {
        ArgumentCaptor<Page<ChatUserMemoryEntity>> captor = ArgumentCaptor.forClass(Page.class);
        verify(memoryMapper, atLeastOnce()).selectPage(captor.capture(), any());
        Page<ChatUserMemoryEntity> last = captor.getValue();
        assertEquals(expectedPage, last.getCurrent());
        assertEquals(expectedSize, last.getSize());
    }

    private Wrapper<ChatUserMemoryEntity> captureWrapper() {
        ArgumentCaptor<Wrapper<ChatUserMemoryEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(memoryMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private static Field fieldOf(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找：审计列定义在 BaseEntity 上
            }
        }
        throw new NoSuchFieldException(name);
    }
}
