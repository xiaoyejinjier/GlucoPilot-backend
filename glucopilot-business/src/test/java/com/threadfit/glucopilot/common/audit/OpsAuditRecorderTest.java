package com.threadfit.glucopilot.common.audit;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计写入基础设施（{@link OpsAuditRecorder}）的语义。
 * <p>
 * 这里守的是"审计是旁路"这条纪律：<b>任何失败都不许把业务动作拖下水</b>。
 * 具体三个失败面——列宽（detail_json 是 varchar(2000)）、写库异常、缺操作人上下文——
 * 每一个在真实环境里都会发生（严格模式超长直接报错、数据库抖动、异步/定时任务线程没有 opsId），
 * 而它们的正确行为都是"降级写下去"或"只记日志"，不是抛异常。
 * <p>
 * 另外用 H2 真库跑一遍 {@link #真实库上按目标回溯与条件查询可用()}：两条查询是给审计查询页（3.31）
 * 用的，Mock 只能证明"方法被调了"，证明不了注解 SQL 真的能跑、过滤与倒序真的是那个语义。
 */
class OpsAuditRecorderTest {

    private OpsAuditLogMapper opsAuditLogMapper;
    private OpsAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        opsAuditLogMapper = mock(OpsAuditLogMapper.class);
        recorder = new OpsAuditRecorder(opsAuditLogMapper);
        RequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------- 列宽与字段降级

    @Test
    void 超长详情被截断到列宽而不是让写库报错() {
        String oversize = "x".repeat(OpsAuditRecorder.DETAIL_JSON_MAX_LENGTH + 500);

        recorder.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", 7L, oversize);

        OpsAuditLogEntity row = captured();
        assertEquals(OpsAuditRecorder.DETAIL_JSON_MAX_LENGTH, row.getDetailJson().length(),
                "detail_json 是 varchar(2000)：超长会被严格模式拒绝，而拒绝又会被吞掉，"
                        + "表现成「审计悄悄少一条」，所以必须显式截断");
    }

    @Test
    void 详情刚好到列宽时不截断() {
        String exact = "y".repeat(OpsAuditRecorder.DETAIL_JSON_MAX_LENGTH);

        recorder.record("ai", "ai_memory_delete", "删除用户AI长期记忆", "user", 7L, exact);

        assertEquals(exact, captured().getDetailJson());
    }

    @Test
    void 目标为空时写0而不是null() {
        recorder.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", null, "{\"count\":0}");

        assertEquals(0L, captured().getTargetId().longValue(), "target_id 列 NOT NULL DEFAULT 0");
    }

    @Test
    void 编码类入参为空时写空串而不是null() {
        recorder.record(null, null, null, null, 7L, null);

        OpsAuditLogEntity row = captured();
        assertEquals("", row.getModuleCode());
        assertEquals("", row.getActionCode());
        assertEquals("", row.getActionName());
        assertEquals("", row.getTargetType());
        assertNull(row.getDetailJson(), "detail_json 列可空，无详情就留 null");
    }

    // ---------------------------------------------------------------- 操作人上下文

    @Test
    void 有opsId时写入操作人与客户端IP() {
        RequestContext.setOpsId(9L);
        RequestContext.setClientIp("10.0.0.9");

        recorder.record("ai", "ai_memory_delete", "删除用户AI长期记忆", "user", 7L, "{\"count\":2}");

        OpsAuditLogEntity row = captured();
        assertEquals(9L, row.getOpsId().longValue());
        assertEquals(9L, row.getCreatedBy().longValue(), "created_by 写 opsId，而不是 C 端 user_id");
        assertEquals("10.0.0.9", row.getClientIp());
        // 姓名/角色刻意不查库补全（查库会给"审计能不能写成功"加一个新的失败点），列 NOT NULL 用空串占位
        assertEquals("", row.getOperatorName());
        assertNull(row.getRoleCode());
        assertNull(row.getRequestUri(), "非 Web 线程没有请求上下文，留 null 而不是编一个");
        assertNull(row.getRequestMethod());
    }

    /**
     * 没有 opsId（异步线程、定时任务、C 端链路误用）时也不能崩：
     * 列都是 NOT NULL，写 0 表达"无具体操作人"，比抛异常让业务失败好得多。
     */
    @Test
    void 缺少opsId时不崩且写占位值() {
        // setUp 已 clear：此刻没有 opsId
        assertDoesNotThrow(() ->
                recorder.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", 7L, "{\"count\":0}"));

        OpsAuditLogEntity row = captured();
        assertEquals(0L, row.getOpsId().longValue());
        assertEquals(0L, row.getCreatedBy().longValue());
    }

    /** C 端线程里有 userId 但没有 opsId：不能把 user_id 混进运营审计（列语义是运营账号）。 */
    @Test
    void 只有userId时不把user_id当作操作人() {
        RequestContext.setUserId(1234L);

        recorder.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", 7L, "{\"count\":1}");

        OpsAuditLogEntity row = captured();
        assertEquals(0L, row.getOpsId().longValue());
        // created_by 显式写死，而不是让它落到 MetaObjectHandler 的"ops 优先、其次 user"逻辑上，
        // 否则 C 端线程调用时会往运营审计里写一个 C 端 user_id，查询页按操作人过滤就串了
        assertEquals(0L, row.getCreatedBy().longValue());
    }

    // ---------------------------------------------------------------- 失败降级

    @Test
    void 写库失败只记日志不向上抛() {
        when(opsAuditLogMapper.insert(any(OpsAuditLogEntity.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() ->
                recorder.record("ai", "ai_memory_delete", "删除用户AI长期记忆", "user", 7L, "{\"count\":2}"),
                "审计是旁路：写不进去只能降级为日志，绝不能让业务动作失败");
        verify(opsAuditLogMapper).insert(any(OpsAuditLogEntity.class));
    }

    @Test
    void 取上下文本身抛异常时也不向上抛() {
        // 覆盖"填字段阶段就出问题"的路径：insert 根本没被调用，但调用方仍必须看到成功
        OpsAuditLogMapper exploding = mock(OpsAuditLogMapper.class);
        when(exploding.insert(any(OpsAuditLogEntity.class))).thenThrow(new IllegalStateException("boom"));
        OpsAuditRecorder failing = new OpsAuditRecorder(exploding);

        assertDoesNotThrow(() ->
                failing.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", 7L, null));
    }

    // ---------------------------------------------------------------- 真实库（H2，MySQL 兼容模式）

    /**
     * 真库验证两条查询（给审计查询页用）：
     * <ol>
     *   <li>{@code selectByTarget} 按 (module_code, target_type, target_id) 过滤并按 id 倒序；</li>
     *   <li>{@code selectPage(Filter)} 的等值/时间/关键词条件生效；</li>
     *   <li>同名的内置分页 {@code selectPage(Page, Wrapper)} 仍然可用——本接口自定义了一个
     *       {@code selectPage(Filter)} 默认方法，需要证明这个重载没有把 MyBatis-Plus 注入的内置方法挤掉。</li>
     * </ol>
     */
    @Test
    void 真实库上按目标回溯与条件查询可用() throws Exception {
        UnpooledDataSource datasource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:audit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE ops_audit_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, ops_id BIGINT NOT NULL DEFAULT 0, "
                    + "operator_name VARCHAR(64) NOT NULL DEFAULT '', role_code VARCHAR(64) NULL, "
                    + "module_code VARCHAR(32) NOT NULL DEFAULT '', action_code VARCHAR(64) NOT NULL, "
                    + "action_name VARCHAR(64) NOT NULL DEFAULT '', target_type VARCHAR(32) NOT NULL, "
                    + "target_id BIGINT NOT NULL DEFAULT 0, detail_json VARCHAR(2000) NULL, "
                    + "request_uri VARCHAR(255) NULL, request_method VARCHAR(10) NULL, client_ip VARCHAR(45) NULL, "
                    + "cost_ms INT NULL, result_code INT NULL, created_by BIGINT NOT NULL DEFAULT 0, "
                    + "gmt_create TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))");
        }
        SqlSessionFactory sessionFactory = auditSqlSessionFactory(datasource);
        // 与生产的 @MapperScan 等价：mapper 必须先注册进配置，MP 才会注入 BaseMapper 的 CRUD
        sessionFactory.getConfiguration().addMapper(OpsAuditLogMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            OpsAuditLogMapper realMapper = session.getMapper(OpsAuditLogMapper.class);
            OpsAuditRecorder realRecorder = new OpsAuditRecorder(realMapper);

            RequestContext.setOpsId(9L);
            realRecorder.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", 7L, "{\"count\":1}");
            realRecorder.record("ai", "ai_memory_delete", "删除用户AI长期记忆", "user", 7L, "{\"count\":2}");
            realRecorder.record("ai", "ai_prompt_save", "保存提示词", "prompt", 5L, "{\"version\":\"v2\"}");
            RequestContext.setOpsId(11L);
            realRecorder.record("ai", "ai_memory_view", "查询用户AI长期记忆", "user", 7L, "{\"count\":3}");

            List<OpsAuditLogEntity> byTarget = realMapper.selectByTarget("ai", "user", 7L, 2);
            assertEquals(2, byTarget.size(), "LIMIT 生效");
            assertEquals("{\"count\":3}", byTarget.get(0).getDetailJson(), "id 倒序：最新的在最前");
            assertEquals(9L, byTarget.get(1).getOpsId().longValue());

            OpsAuditLogMapper.Filter byModule = new OpsAuditLogMapper.Filter(
                    null, "ai", null, null, null, null, null, null);
            // 先证明四条都真的落库：Recorder 吞异常，插不进去也不会报错，只能靠这种计数暴露
            assertEquals(4, realMapper.selectPage(byModule).size());

            OpsAuditLogMapper.Filter byKeyword = new OpsAuditLogMapper.Filter(
                    null, null, null, null, null, null, null, "删除");
            List<OpsAuditLogEntity> matched = realMapper.selectPage(byKeyword);
            assertEquals(1, matched.size(), "关键词匹配动作名");
            assertEquals("ai_memory_delete", matched.getFirst().getActionCode());

            OpsAuditLogMapper.Filter byWindow = new OpsAuditLogMapper.Filter(null, "ai", null, null, null,
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), null);
            assertEquals(4, realMapper.selectPage(byWindow).size(), "时间区间条件生效");
            assertTrue(realMapper.selectPage(new OpsAuditLogMapper.Filter(null, "ai", null, null, null,
                    LocalDateTime.now().plusDays(1), null, null)).isEmpty(), "起始时间在未来时查不到任何行");

            // 内置分页 + 条件：证明自定义的 selectPage(Filter) 没有把注入的 selectPage(Page, Wrapper) 挤掉
            Page<OpsAuditLogEntity> page = realMapper.selectPage(new Page<>(1, 3),
                    new LambdaQueryWrapper<OpsAuditLogEntity>()
                            .eq(OpsAuditLogEntity::getModuleCode, "ai")
                            .orderByDesc(OpsAuditLogEntity::getId));
            assertEquals(3, page.getRecords().size(), "分页插件真的生效（4 条里取 3 条）");
            assertEquals(4L, page.getTotal());
            assertEquals(11L, page.getRecords().getFirst().getCreatedBy().longValue(),
                    "倒序取到的第一条是最后写入的那条（ops 11）");
        }
    }

    // ---------------------------------------------------------------- 辅助

    private OpsAuditLogEntity captured() {
        ArgumentCaptor<OpsAuditLogEntity> captor = ArgumentCaptor.forClass(OpsAuditLogEntity.class);
        verify(opsAuditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 真实 MP 会话（与 PersistenceConfiguration 的搭配一致：MP 配置 + 全局配置 + 分页插件）。 */
    private static SqlSessionFactory auditSqlSessionFactory(DataSource datasource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        // 与生产同一个填充器：gmt_create 是 fill 字段，insert 语句无条件带上它，
        // 没有 MetaObjectHandler 就会写 NULL 并被 NOT NULL 拒绝——而 Recorder 会把这异常吞掉，
        // 表现成"表里一行都没有"（本测试第一次跑就是这么暴露出来的）
        global.setMetaObjectHandler(new AuditMetaObjectHandler());
        factory.setGlobalConfig(global);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        factory.setPlugins(interceptor);
        return factory.getObject();
    }
}
