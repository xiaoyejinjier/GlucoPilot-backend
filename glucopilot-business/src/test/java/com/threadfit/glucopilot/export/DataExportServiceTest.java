package com.threadfit.glucopilot.export;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.export.mapper.DataExportMapper;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

class DataExportServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DataExportService service;
    private java.sql.Connection connection;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:exports_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        connection = ds.getConnection();
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,username"
                        + " VARCHAR(64),account_status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname"
                        + " VARCHAR(64),is_deleted INT DEFAULT 0)");
        String schema =
                new ClassPathResource("db/migration/V1__init_schema.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        for (String table : List.of("data_export_task", "glucose_record", "meal_record")) {
            int start = schema.indexOf("CREATE TABLE " + table + " (");
            String ddl =
                    schema.substring(start, schema.indexOf(';', start))
                            .replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY")
                            .replace("uk_user_date_node", "uk_" + table + "_date_node")
                            .replace("idx_user_date", "idx_" + table + "_date");
            try (var s = connection.createStatement()) {
                s.execute(ddl);
            }
        }
        var migration =
                new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/V34__drafts_notifications_exports.sql"));
        migration.setSqlScriptEncoding("UTF-8");
        migration.populate(connection);
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V40__export_task_user_removal.sql"))
                .populate(connection);
        jdbc.update("INSERT INTO user_account(id,username) VALUES(1,'one'),(2,'two')");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(1,'用户甲'),(2,'用户乙')");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        config.addMapper(DataExportMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        service = new DataExportService(session.getMapper(DataExportMapper.class));
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            jdbc.execute("DROP ALL OBJECTS");
        } finally {
            connection.close();
        }
    }

    private DataExportService.Task create(long user, String key) {
        return tx.execute(
                s ->
                        service.create(
                                user,
                                new DataExportService.Create(
                                        LocalDate.now().minusDays(6), LocalDate.now(), key)));
    }

    @Test
    void accountCleanupDeletesOnlyOwnedTasksAndPdfSnapshotsAndCanBeRetried() {
        long first = Long.parseLong(create(1, "export-purge-first").id());
        long second = Long.parseLong(create(2, "export-purge-second").id());
        tx.execute(status -> service.generate(1L, first));
        tx.execute(status -> service.generate(2L, second));
        byte[] otherSnapshot = service.file(2L, second);

        tx.executeWithoutResult(status -> service.purgeAccountData(1));
        tx.executeWithoutResult(status -> service.purgeAccountData(1));

        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM data_export_task WHERE user_id=1", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM data_export_file WHERE task_id=?",
                        Integer.class,
                        first));
        assertThrows(BizException.class, () -> service.get(null, first));
        assertEquals("done", service.get(2L, second).status());
        assertArrayEquals(otherSnapshot, service.file(2L, second));
    }

    @Test
    void exportContainsOnlyOwnedRealRecordsAndDownloadsImmutableActualPdf() throws Exception {
        jdbc.update(
                "INSERT INTO glucose_record(user_id,record_date,node_code,glucose_value)"
                    + " VALUES(1,CURRENT_DATE,'before_breakfast',6.2),(2,CURRENT_DATE,'before_breakfast',9.1)");
        jdbc.update(
                "INSERT INTO"
                    + " glucose_record(user_id,record_date,node_code,glucose_value,is_deleted,deleted_at)"
                    + " VALUES(1,CURRENT_DATE,'before_sleep',7.1,1,CURRENT_TIMESTAMP)");
        jdbc.update(
                "INSERT INTO meal_record(user_id,record_date,node_code,foods_text,carbs_g)"
                    + " VALUES(1,CURRENT_DATE,'lunch','早餐记录',26.0),(2,CURRENT_DATE,'lunch','乙的记录',55.0)");
        var task = create(1, "export-real-001");
        long id = Long.parseLong(task.id());
        assertEquals(task.id(), create(1, "export-real-001").id());
        var done = tx.execute(s -> service.generate(1L, id));
        assertEquals("done", done.status());
        assertEquals(1, done.glucoseCount());
        assertEquals(1, done.mealCount());
        byte[] bytes = service.file(1L, id);
        assertArrayEquals(bytes, service.file(null, id));
        String text = pdfText(bytes);
        assertTrue(text.contains("6.2"));
        assertTrue(text.contains("早餐记录"));
        assertTrue(text.contains("用户甲"));
        assertFalse(text.contains("9.1"));
        assertFalse(text.contains("7.1"));
        assertFalse(text.contains("乙的记录"));
        assertThrows(BizException.class, () -> service.file(2L, id));
        assertThrows(BizException.class, () -> tx.execute(s -> service.generate(2L, id)));
        jdbc.update("UPDATE glucose_record SET glucose_value=8.1 WHERE user_id=1 AND is_deleted=0");
        tx.execute(s -> service.generate(1L, id));
        assertArrayEquals(bytes, service.file(1L, id));
        assertEquals(1, service.list(1L, null, null, new PageQuery()).getTotal());
        assertEquals(0, service.list(2L, null, null, new PageQuery()).getTotal());
        assertEquals(1, service.list(null, "done", "用户甲", new PageQuery()).getTotal());
    }

    @Test
    void emptyDataProducesExplicitEmptyPdfAndInvalidRangesOrChangedIdempotencyAreRejected()
            throws Exception {
        var task = create(1, "export-empty-01");
        long id = Long.parseLong(task.id());
        tx.execute(s -> service.generate(1L, id));
        String text = pdfText(service.file(1L, id));
        assertTrue(text.contains("所选日期内暂无血糖记录。"));
        assertTrue(text.contains("所选日期内暂无饮食记录。"));
        assertTrue(text.contains("血糖记录 0 条"));
        assertThrows(
                BizException.class,
                () ->
                        tx.execute(
                                s ->
                                        service.create(
                                                1,
                                                new DataExportService.Create(
                                                        LocalDate.now(),
                                                        LocalDate.now().plusDays(1),
                                                        "export-future-1"))));
        assertThrows(
                BizException.class,
                () ->
                        tx.execute(
                                s ->
                                        service.create(
                                                1,
                                                new DataExportService.Create(
                                                        LocalDate.now().minusYears(2),
                                                        LocalDate.now(),
                                                        "export-too-long"))));
        assertThrows(
                BizException.class,
                () ->
                        tx.execute(
                                s ->
                                        service.create(
                                                1,
                                                new DataExportService.Create(
                                                        LocalDate.now(),
                                                        LocalDate.now(),
                                                        "export-empty-01"))));
    }

    @Test
    void failedGenerationIsRecordedAndNeverProvidesFakeDownload() {
        var task = create(1, "export-failure-1");
        long id = Long.parseLong(task.id());
        jdbc.execute("DROP TABLE meal_record");
        var failed = tx.execute(s -> service.generate(1L, id));
        assertEquals("failed", failed.status());
        assertEquals("文件生成失败，请稍后重试", failed.error());
        assertThrows(BizException.class, () -> service.file(1L, id));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM data_export_file", Integer.class));
    }

    @Test
    void userRemovalIsPrivateIdempotentAndPreservesAdminTaskAndPdf() throws Exception {
        long id = Long.parseLong(create(1, "export-remove-01").id());
        tx.execute(s -> service.generate(1L, id));
        byte[] original = service.file(1L, id);
        assertThrows(BizException.class, () -> tx.executeWithoutResult(s -> service.remove(2, id)));
        tx.executeWithoutResult(s -> service.remove(1, id));
        tx.executeWithoutResult(s -> service.remove(1, id));
        assertEquals(0, service.list(1L, null, null, new PageQuery()).getTotal());
        assertEquals(1, service.list(null, null, null, new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> service.get(1L, id));
        assertThrows(BizException.class, () -> service.file(1L, id));
        assertThrows(BizException.class, () -> tx.execute(s -> service.generate(1L, id)));
        assertArrayEquals(original, service.file(null, id));
        assertEquals("done", tx.execute(s -> service.generate(null, id)).status());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT is_deleted FROM data_export_task WHERE id=?", Integer.class, id));
        assertThrows(BizException.class, () -> create(1, "export-remove-01"));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM data_export_file", Integer.class));
    }

    @Test
    void removedPendingTasksRemainActionableForAdminAndStillCountTowardDailyLimit() {
        for (int i = 0; i < 20; i++) {
            long id = Long.parseLong(create(1, "export-limit-" + i).id());
            tx.executeWithoutResult(s -> service.remove(1, id));
        }
        assertThrows(BizException.class, () -> create(1, "export-limit-excess"));
        assertEquals(20, service.list(null, null, null, new PageQuery()).getTotal());
        assertEquals(0, service.list(1L, null, null, new PageQuery()).getTotal());
        long id = jdbc.queryForObject("SELECT MIN(id) FROM data_export_task", Long.class);
        assertEquals("done", tx.execute(s -> service.generate(null, id)).status());
    }

    @Test
    void typedDatesAndLegacyScopeDefaultsAreReadByRealMyBatis() {
        var now = java.time.LocalDateTime.of(2026, 10, 23, 12, 30);
        jdbc.update(
                "INSERT INTO"
                    + " data_export_task(id,user_id,export_scope,task_status,gmt_create,finished_at)"
                    + " VALUES(7,1,1,2,?,?)",
                now,
                now.plusMinutes(1));
        var task = service.get(1L, 7);
        assertEquals(now, task.createdAt());
        assertEquals(now.plusMinutes(1), task.finishedAt());
        assertEquals(now.toLocalDate(), task.endDate());
        assertEquals(now.toLocalDate().minusDays(6), task.startDate());
        assertEquals("done", task.status());
        jdbc.update("UPDATE data_export_task SET export_scope=2 WHERE id=7");
        assertEquals(now.toLocalDate().minusDays(29), service.get(1L, 7).startDate());
    }

    @Test
    void legacyXlsxDownloadConvertsSavedSnapshotToPdfWithoutRequeryingOrOverwritingIt()
            throws Exception {
        var task = create(1, "export-legacy-01");
        long id = Long.parseLong(task.id());
        byte[] saved;
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var glucose = book.createSheet("血糖记录");
            glucose.createRow(0).createCell(0).setCellValue("日期");
            var row = glucose.createRow(1);
            row.createCell(0).setCellValue(LocalDate.now().toString());
            row.createCell(1).setCellValue("fasting");
            row.createCell(2).setCellValue(5.6);
            row.createCell(5).setCellValue("旧文件的真实备注");
            book.createSheet("饮食记录").createRow(0).createCell(0).setCellValue("日期");
            book.write(out);
            saved = out.toByteArray();
        }
        jdbc.update(
                "INSERT INTO data_export_file(task_id,file_bytes,glucose_count,meal_count)"
                        + " VALUES(?,?,1,0)",
                id,
                saved);
        jdbc.update("UPDATE data_export_task SET task_status=2 WHERE id=?", id);
        jdbc.execute("DROP TABLE glucose_record");
        jdbc.execute("DROP TABLE meal_record");
        String text = pdfText(service.file(1L, id));
        assertTrue(text.contains("5.6"));
        assertTrue(text.contains("旧文件的真实备注"));
        assertArrayEquals(
                saved,
                jdbc.queryForObject(
                        "SELECT file_bytes FROM data_export_file WHERE task_id=?",
                        byte[].class,
                        id));
        assertThrows(BizException.class, () -> service.file(2L, id));
    }

    @Test
    void listFiltersBindLiteralKeywordsAndPageWithoutLeakingOtherUsers() {
        create(1, "export-page-001");
        create(1, "export-page-002");
        create(2, "export-page-003");
        jdbc.update("UPDATE user_profile SET nickname=? WHERE user_id=1", "糖友_%测试");
        var q = new PageQuery();
        q.setPageNum(2);
        q.setPageSize(1);
        var page = service.list(null, "pending", "_%", q);
        assertEquals(2, page.getTotal());
        assertEquals(1, page.getRecords().size());
        assertEquals("1", page.getRecords().getFirst().userId());
        assertEquals(0, service.list(2L, null, "_%", new PageQuery()).getTotal());
        assertEquals(0, service.list(null, null, "' OR 1=1 --", new PageQuery()).getTotal());
        assertThrows(
                BizException.class, () -> service.list(null, "unknown", null, new PageQuery()));
    }

    @Test
    void concurrentRequestsRespectIdempotencyAndDailyQuota() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 4; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    latch.await();
                                    return create(1, "export-race-same").id();
                                }));
            latch.countDown();
            var ids = new HashSet<String>();
            for (var result : results)
                ids.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            for (int i = 0; i < 18; i++) create(1, "export-race-prefill-" + i);
            var start = new java.util.concurrent.CountDownLatch(1);
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                final int n = i;
                attempts.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        create(1, "export-race-limit-" + n);
                                        return true;
                                    } catch (BizException ex) {
                                        assertEquals("今日导出申请较多，请稍后再试", ex.getMessage());
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts)
                if (attempt.get(10, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(
                    20,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM data_export_task WHERE user_id=1",
                            Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    private static String pdfText(byte[] bytes) throws Exception {
        assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII));
        try (var pdf = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(pdf);
        }
    }
}
