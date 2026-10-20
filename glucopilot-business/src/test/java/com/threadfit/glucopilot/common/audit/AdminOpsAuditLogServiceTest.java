package com.threadfit.glucopilot.common.audit;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计查询接口（契约 3.31）的语义。
 * <p>
 * 这一层要钉住的东西都不在"能不能查出来"上（那是真库用例的事），而在几个容易被"顺手优化"改坏的点：
 * <ol>
 *   <li><b>只读</b>：查询不写审计。写一条"查看审计"会让审计表随浏览自激增长，
 *       还会把真正的业务动作挤出筛选结果（见 {@link AdminOpsAuditLogService} 类注释）；</li>
 *   <li><b>分页夹紧</b>：pageSize 缺省 20、上限 100，越界夹紧而不是 400（与 4.14 / 5.3 同口径）；</li>
 *   <li><b>16 个字段逐字对齐</b>：管理端页面已按这些字段名写好，漏一个就是页面上一片空白，
 *       而编译、启动、鉴权全都不会报错；</li>
 *   <li><b>total 是全量总数</b>而不是本页条数——否则翻页组件会显示"只有 20 条"。</li>
 * </ol>
 * 另外用 H2 真库跑一遍 {@link #真实库上分页与四个筛选条件都生效()}：分页依赖
 * {@code PaginationInnerInterceptor} 的 COUNT + LIMIT，条件依赖 lambda 推导列名，
 * Mock 只能证明"方法被调了"。
 */
class AdminOpsAuditLogServiceTest {

    private static final Long TARGET_USER_ID = 18L;

    private OpsAuditLogMapper opsAuditLogMapper;
    private AdminOpsAuditLogService service;

    @BeforeEach
    void setUp() {
        opsAuditLogMapper = mock(OpsAuditLogMapper.class);
        service = new AdminOpsAuditLogService(opsAuditLogMapper);
    }

    // ---------------------------------------------------------------- 字段映射

    @Test
    void 十六条字段逐字落进出参() {
        OpsAuditLogEntity entity = row(31L, 99L, "ai_memory_delete");
        doReturn(page(List.of(entity), 1L)).when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        OpsAuditLogDtos.AuditLogRow row = service.list("ai", null, null, null, 1, 20)
                .getRecords().getFirst();

        assertEquals(31L, row.getId());
        assertEquals(99L, row.getOpsId());
        assertEquals("运营小王", row.getOperatorName());
        assertEquals("operator", row.getRoleCode());
        assertEquals("ai", row.getModuleCode());
        assertEquals("ai_memory_delete", row.getActionCode());
        assertEquals("删除用户AI长期记忆", row.getActionName());
        assertEquals("user", row.getTargetType());
        assertEquals(TARGET_USER_ID, row.getTargetId());
        assertEquals("{\"count\":2}", row.getDetailJson());
        assertEquals("/admin/v1/users/18/chat-memories", row.getRequestUri());
        assertEquals("DELETE", row.getRequestMethod());
        assertEquals("10.0.0.9", row.getClientIp());
        assertEquals(37, row.getCostMs());
        assertEquals(0, row.getResultCode());
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 30, 5), row.getGmtCreate());
    }

    /**
     * 可空列（异步线程写入的行没有请求上下文、没有耗时）必须原样给 null，
     * 不能兜成 0 / 空串：查询页要能区分"没记录"与"记录为 0"。
     */
    @Test
    void 可空字段保持null而不是兜底成零值() {
        OpsAuditLogEntity entity = row(32L, 0L, "ai_memory_view");
        entity.setRoleCode(null);
        entity.setDetailJson(null);
        entity.setRequestUri(null);
        entity.setRequestMethod(null);
        entity.setClientIp(null);
        entity.setCostMs(null);
        entity.setResultCode(null);
        doReturn(page(List.of(entity), 1L)).when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        OpsAuditLogDtos.AuditLogRow row = service.list(null, null, null, null, 1, 20)
                .getRecords().getFirst();

        assertNull(row.getRoleCode());
        assertNull(row.getDetailJson(), "detail_json 原样透出：解析失败也不能让整页 500");
        assertNull(row.getRequestUri());
        assertNull(row.getRequestMethod());
        assertNull(row.getClientIp());
        assertNull(row.getCostMs());
        assertNull(row.getResultCode());
    }

    @Test
    void total取全量总数而不是本页条数() {
        doReturn(page(List.of(row(31L, 99L, "ai_memory_view"), row(32L, 99L, "ai_memory_view")), 137L))
                .when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        OpsAuditLogDtos.AuditLogList list = service.list("ai", null, null, null, 2, 20);

        assertEquals(2, list.getRecords().size());
        assertEquals(137L, list.getTotal(), "total 是全量总数：否则翻页组件会以为只有 20 条");
    }

    @Test
    void 没有命中时返回空列表与零总数() {
        doReturn(page(List.of(), 0L)).when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        OpsAuditLogDtos.AuditLogList list = service.list("ai", "ai_memory_delete", "user", 18L, 1, 20);

        assertTrue(list.getRecords().isEmpty());
        assertEquals(0L, list.getTotal());
    }

    // ---------------------------------------------------------------- 分页夹紧

    @Test
    void 分页参数越界被夹紧而不是报错() {
        doReturn(page(List.of(), 0L)).when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        service.list(null, null, null, null, null, null);
        assertPageRequest(1, 20);
        service.list(null, null, null, null, 0, 0);
        assertPageRequest(1, 20);
        service.list(null, null, null, null, -3, -20);
        assertPageRequest(1, 20);
        service.list(null, null, null, null, 4, 500);
        assertPageRequest(4, 100);
    }

    @Test
    void 夹紧逻辑本身的边界值() {
        assertEquals(20, AdminOpsAuditLogService.clampSize(null));
        assertEquals(20, AdminOpsAuditLogService.clampSize(0));
        assertEquals(20, AdminOpsAuditLogService.clampSize(-1));
        assertEquals(1, AdminOpsAuditLogService.clampSize(1));
        assertEquals(100, AdminOpsAuditLogService.clampSize(100));
        assertEquals(100, AdminOpsAuditLogService.clampSize(101));
    }

    // ---------------------------------------------------------------- 只读

    /**
     * 查询页不能给审计表添乱：一次查询只对外发一条 SELECT，绝不 INSERT。
     * <p>
     * 这条不是"防某人手滑"，而是防一个很自然的产品诉求——"谁看了审计日志也该留痕吧？"
     * 一旦答应它，按 moduleCode=ai 筛选的结果就会被自己的查看记录刷屏，
     * 真正的业务动作被挤到后面几页，查询页从"台账"退化成"访问日志"。
     */
    @Test
    void 查询不写任何审计记录() {
        doReturn(page(List.of(row(31L, 99L, "ai_memory_view")), 1L))
                .when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        service.list("ai", null, null, TARGET_USER_ID, 1, 20);

        verify(opsAuditLogMapper, never()).insert(any(OpsAuditLogEntity.class));
    }

    // ---------------------------------------------------------------- 真实库（H2，MySQL 兼容模式）

    /**
     * 真库验证三件事（Mock 证明不了）：
     * <ol>
     *   <li>{@code PaginationInnerInterceptor} 真的发出了 COUNT + LIMIT，{@code total} 是全量；</li>
     *   <li>四个等值条件（模块/动作/目标类型/目标主键）都真的进了 WHERE，空值不参与过滤；</li>
     *   <li>排序是 {@code id DESC}（同一毫秒内的多条也稳定，翻页不会重复或漏行）。</li>
     * </ol>
     */
    @Test
    void 真实库上分页与四个筛选条件都生效() throws Exception {
        UnpooledDataSource datasource = auditDatasource();
        createAuditTable(datasource);
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO ops_audit_log "
                    + "(id,ops_id,operator_name,role_code,module_code,action_code,action_name,target_type,target_id,"
                    + "detail_json,request_uri,request_method,client_ip,cost_ms,result_code,created_by,gmt_create) VALUES "
                    + "(1,9,'运营甲','operator','ai','ai_memory_view','查询用户AI长期记忆','user',18,'{\"count\":1}',"
                    + "'/admin/v1/users/18/chat-memories','GET','10.0.0.1',12,0,9,TIMESTAMP '2026-10-19 08:00:00'),"
                    + "(2,9,'运营甲','operator','ai','ai_memory_delete','删除用户AI长期记忆','user',18,'{\"count\":2}',"
                    + "'/admin/v1/users/18/chat-memories','DELETE','10.0.0.1',25,0,9,TIMESTAMP '2026-10-19 08:01:00'),"
                    + "(3,11,'运营乙','operator','ai','ai_memory_view','查询用户AI长期记忆','user',21,'{\"count\":3}',"
                    + "'/admin/v1/users/21/chat-memories','GET','10.0.0.2',9,0,11,TIMESTAMP '2026-10-19 08:02:00'),"
                    + "(4,11,'运营乙','operator','food','food_update','修改食物库','food',77,'{\"field\":\"gi\"}',"
                    + "'/admin/v1/foods/77','PUT','10.0.0.2',5,0,11,TIMESTAMP '2026-10-19 08:03:00')");
        }
        SqlSessionFactory sessionFactory = auditSqlSessionFactory(datasource);
        // 与生产的 @MapperScan 等价：mapper 必须先注册进配置，MP 才会注入 BaseMapper 的 CRUD 与分页
        sessionFactory.getConfiguration().addMapper(OpsAuditLogMapper.class);
        try (SqlSession session = sessionFactory.openSession(true)) {
            OpsAuditLogMapper realMapper = session.getMapper(OpsAuditLogMapper.class);
            AdminOpsAuditLogService realService = new AdminOpsAuditLogService(realMapper);

            OpsAuditLogDtos.AuditLogList all = realService.list(null, null, null, null, 1, 20);
            assertEquals(4L, all.getTotal(), "不加条件时总数是全表");
            assertEquals(List.of(4L, 3L, 2L, 1L), all.getRecords().stream()
                    .map(OpsAuditLogDtos.AuditLogRow::getId).toList(), "id 倒序：翻页才可复现");
            assertEquals(11L, all.getRecords().getFirst().getOpsId().longValue());

            OpsAuditLogDtos.AuditLogList ai = realService.list("ai", null, null, null, 1, 20);
            assertEquals(3L, ai.getTotal(), "按模块过滤");

            OpsAuditLogDtos.AuditLogList byUser = realService.list("ai", null, "user", 18L, 1, 20);
            assertEquals(2L, byUser.getTotal(), "模块 + 目标类型 + 目标主键都被查看过的那个用户");
            assertEquals(List.of("ai_memory_delete", "ai_memory_view"), byUser.getRecords().stream()
                    .map(OpsAuditLogDtos.AuditLogRow::getActionCode).toList());

            assertEquals(1L, realService.list("ai", "ai_memory_delete", "user", 18L, 1, 20).getTotal(),
                    "再叠加动作编码");
            assertEquals(0L, realService.list("ai", "ai_memory_delete", "user", 21L, 1, 20).getTotal(),
                    "条件之间是 AND：目标 21 没有删除记录");

            // 分页本身：总数仍是 4，本页只有 3 条
            OpsAuditLogDtos.AuditLogList firstPage = realService.list(null, null, null, null, 1, 3);
            assertEquals(3, firstPage.getRecords().size());
            assertEquals(4L, firstPage.getTotal(), "翻页时 total 必须保持全量总数");
            OpsAuditLogDtos.AuditLogList secondPage = realService.list(null, null, null, null, 2, 3);
            assertEquals(1, secondPage.getRecords().size(), "第二页只剩 1 条");
            assertEquals(1L, secondPage.getRecords().getFirst().getId());
            // 两页之间不重叠、不遗漏
            assertEquals(4, firstPage.getRecords().size() + secondPage.getRecords().size());
        }
    }

    // ---------------------------------------------------------------- 契约（HTTP 层）

    /**
     * 路径、权限串与出参形状。
     * <p>
     * standalone MockMvc 不装配安全链，所以这里断言的是"路由与出参契约"，权限点用注解上的字符串钉住：
     * 拼错成 {@code ai:audit:read} 这类问题编译期不报错，只会在线上变成"运营点进去 403"。
     */
    @Test
    void 契约3_31可访问且records与total都在data里() throws Exception {
        doReturn(page(List.of(row(31L, 99L, "ai_memory_delete")), 1L))
                .when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        MockMvcBuilders.standaloneSetup(new AdminOpsAuditLogController(service)).build()
                .perform(get("/admin/v1/ops-audit-logs")
                        .param("moduleCode", "ai")
                        .param("actionCode", "ai_memory_delete")
                        .param("targetType", "user")
                        .param("targetId", "18")
                        .param("pageNum", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].id").value("31"))
                .andExpect(jsonPath("$.data.records[0].opsId").value("99"))
                .andExpect(jsonPath("$.data.records[0].operatorName").value("运营小王"))
                .andExpect(jsonPath("$.data.records[0].roleCode").value("operator"))
                .andExpect(jsonPath("$.data.records[0].moduleCode").value("ai"))
                .andExpect(jsonPath("$.data.records[0].actionCode").value("ai_memory_delete"))
                .andExpect(jsonPath("$.data.records[0].actionName").value("删除用户AI长期记忆"))
                .andExpect(jsonPath("$.data.records[0].targetType").value("user"))
                .andExpect(jsonPath("$.data.records[0].targetId").value("18"))
                .andExpect(jsonPath("$.data.records[0].detailJson").value("{\"count\":2}"))
                .andExpect(jsonPath("$.data.records[0].requestUri").value("/admin/v1/users/18/chat-memories"))
                .andExpect(jsonPath("$.data.records[0].requestMethod").value("DELETE"))
                .andExpect(jsonPath("$.data.records[0].clientIp").value("10.0.0.9"))
                .andExpect(jsonPath("$.data.records[0].costMs").value(37))
                .andExpect(jsonPath("$.data.records[0].resultCode").value(0))
                .andExpect(jsonPath("$.data.records[0].gmtCreate").exists())
                .andExpect(jsonPath("$.data.total").value(1));

        assertEquals("@ss.hasPermi('ai:audit:view')", permissionOf());
    }

    /** 不带任何筛选参数也要可用（运营页首屏就是"全部模块"的样子）。 */
    @Test
    void 不带筛选参数时也能查() throws Exception {
        doReturn(page(List.of(), 0L)).when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        MockMvcBuilders.standaloneSetup(new AdminOpsAuditLogController(service)).build()
                .perform(get("/admin/v1/ops-audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    /** 输出里不能出现 PageResult 的那套回显字段：契约只有 records + total。 */
    @Test
    void 出参里没有分页回显字段() throws Exception {
        doReturn(page(List.of(), 0L)).when(opsAuditLogMapper).selectPage(any(Page.class), any(OpsAuditLogMapper.Filter.class));

        MockMvcBuilders.standaloneSetup(new AdminOpsAuditLogController(service)).build()
                .perform(get("/admin/v1/ops-audit-logs"))
                .andExpect(jsonPath("$.data.pageNum").doesNotExist())
                .andExpect(jsonPath("$.data.pageSize").doesNotExist())
                .andExpect(jsonPath("$.data.pages").doesNotExist());
    }

    // ---------------------------------------------------------------- 辅助

    private static String permissionOf() throws Exception {
        return AdminOpsAuditLogController.class
                .getMethod("list", String.class, String.class, String.class, Long.class, Integer.class, Integer.class)
                .getAnnotation(PreAuthorize.class).value();
    }

    private static OpsAuditLogEntity row(Long id, Long opsId, String actionCode) {
        OpsAuditLogEntity row = new OpsAuditLogEntity();
        row.setId(id);
        row.setOpsId(opsId);
        row.setOperatorName("运营小王");
        row.setRoleCode("operator");
        row.setModuleCode("ai");
        row.setActionCode(actionCode);
        row.setActionName("删除用户AI长期记忆");
        row.setTargetType("user");
        row.setTargetId(TARGET_USER_ID);
        row.setDetailJson("{\"count\":2}");
        row.setRequestUri("/admin/v1/users/18/chat-memories");
        row.setRequestMethod("DELETE");
        row.setClientIp("10.0.0.9");
        row.setCostMs(37);
        row.setResultCode(0);
        row.setGmtCreate(LocalDateTime.of(2026, 10, 19, 8, 30, 5));
        return row;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Page<OpsAuditLogEntity> page(List<OpsAuditLogEntity> records, long total) {
        Page<OpsAuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    /** 断言最近一次查询实际用的分页参数（夹紧逻辑的最终结果）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertPageRequest(long expectedPage, long expectedSize) {
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(opsAuditLogMapper, org.mockito.Mockito.atLeastOnce()).selectPage(captor.capture(), any(OpsAuditLogMapper.Filter.class));
        Page<?> last = captor.getValue();
        assertEquals(expectedPage, last.getCurrent());
        assertEquals(expectedSize, last.getSize());
    }

    private static UnpooledDataSource auditDatasource() {
        return new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:opsaudit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** 建表：列与 V1 迁移的 ops_audit_log 对齐（只追加，没有 gmt_modified / is_deleted）。 */
    private static void createAuditTable(DataSource datasource) throws Exception {
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
    }

    /** 真实 MP 会话：与生产同一套（MP 配置 + 全局配置 + 分页插件 + 审计字段填充器）。 */
    private static SqlSessionFactory auditSqlSessionFactory(DataSource datasource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new AuditMetaObjectHandler());
        factory.setGlobalConfig(global);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        factory.setPlugins(interceptor);
        return factory.getObject();
    }
}
