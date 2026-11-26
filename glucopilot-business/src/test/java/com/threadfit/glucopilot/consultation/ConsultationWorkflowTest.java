package com.threadfit.glucopilot.consultation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.*;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.payment.service.SandboxTransferGateway;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalTransferService;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalTransferStore;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

class ConsultationWorkflowTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ConsultationService service;
    java.sql.Connection schemaConnection;

    @Configuration
    @EnableTransactionManagement
    static class Beans {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:consult_"
                            + UUID.randomUUID()
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    "sa",
                    "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        SqlSessionFactory factory(DataSource ds) {
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(
                    new Environment("test", new SpringManagedTransactionFactory(), ds));
            config.addMapper(ConsultationMapper.class);
            config.addMapper(com.threadfit.glucopilot.consultation.mapper.ConsultationConversationMapper.class);
            config.addMapper(SandboxPaymentMapper.class);
            config.addMapper(
                    com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper.class);
            return new MybatisSqlSessionFactoryBuilder().build(config);
        }

        @Bean
        ConsultationMapper consultationMapper(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(ConsultationMapper.class);
        }

        @Bean
        SandboxPaymentMapper walletMapper(SqlSessionFactory f) {
            return new SqlSessionTemplate(f).getMapper(SandboxPaymentMapper.class);
        }

        @Bean
        ConsultationService service(ConsultationMapper m, SandboxPaymentMapper w) {
            return new ConsultationService(m, w);
        }

        @Bean
        AlipaySandboxProperties sandboxProperties() {
            var props = new AlipaySandboxProperties();
            props.setEnabled(true);
            props.setAppId("2026000000000001");
            props.setSellerId("2088000000000001");
            props.setPrivateKey("fixture_not_used_for_network");
            props.setPublicKey("fixture_not_used_for_network");
            return props;
        }

        @Bean
        SandboxTransferGateway transferGateway() {
            return mock(SandboxTransferGateway.class);
        }

        @Bean
        WithdrawalTransferStore transferStore(
                SqlSessionFactory f, AlipaySandboxProperties properties) {
            return new WithdrawalTransferStore(
                    new SqlSessionTemplate(f).getMapper(WithdrawalMapper.class), properties);
        }

        @Bean
        WithdrawalTransferService transfers(
                SqlSessionFactory f,
                WithdrawalTransferStore store,
                SandboxTransferGateway gateway) {
            return new WithdrawalTransferService(
                    store, new SqlSessionTemplate(f).getMapper(WithdrawalMapper.class), gateway);
        }

        @Bean
        com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService withdrawals(
                SqlSessionFactory f,
                ConsultationMapper m,
                SandboxPaymentMapper w,
                AlipaySandboxProperties properties) {
            return new com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService(
                    new SqlSessionTemplate(f)
                            .getMapper(
                                    com.threadfit.glucopilot.wallet.withdrawal.mapper
                                            .WithdrawalMapper.class),
                    w,
                    m,
                    properties);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Beans.class);
        var ds = context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(ds);
        schemaConnection = ds.getConnection();
        ddl(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT DEFAULT"
                        + " 0,account_status INT DEFAULT 1,wallet_activated_at TIMESTAMP)");
        jdbc.update(
                "INSERT INTO user_account(id,wallet_activated_at)"
                    + " VALUES(1,CURRENT_TIMESTAMP),(2,CURRENT_TIMESTAMP),(3,CURRENT_TIMESTAMP),(4,CURRENT_TIMESTAMP)");
        // 与生产表归属一致：昵称属于资料表，账号表不包含 nickname。
        ddl(
                "CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname"
                        + " VARCHAR(50),is_deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(1,'patient'),(2,'doctor')");
        tables("V64__wallet_recharge_and_consultation_orders.sql", "consultation_order");
        tables("V66__alipay_sandbox_recharge.sql", "wallet_sandbox_account");
        tables(
                "V67__consultation_workflow.sql",
                "consultation_doctor",
                "consultation_request",
                "consultation_report",
                "consultation_wallet_entry");
        tables("V96__consultation_health_topics.sql", "consultation_topic");
        try (var stream =
                getClass()
                        .getResourceAsStream("/db/migration/V96__consultation_health_topics.sql")) {
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("INSERT INTO");
            ddl(sql.substring(start, sql.indexOf(';', start)));
        }
        ddl("ALTER TABLE consultation_request DROP CONSTRAINT ck_consultation_topic");
        ddl(
                "ALTER TABLE consultation_request ADD CONSTRAINT fk_consultation_request_topic"
                    + " FOREIGN KEY(topic) REFERENCES consultation_topic(code)");
        tables(
                "V70__consultation_communication_and_withdrawals.sql",
                "consultation_request_image",
                "consultation_message",
                "wallet_withdrawal_order",
                "wallet_withdrawal_event",
                "wallet_work_calendar");
        ddl("ALTER TABLE wallet_withdrawal_order DROP CONSTRAINT ck_withdrawal_sandbox");
        ddl(
                "ALTER TABLE wallet_withdrawal_order ADD CONSTRAINT ck_withdrawal_sandbox"
                        + " CHECK(provider IN ('SANDBOX_SIMULATION','ALIPAY_SANDBOX_TRANSFER'))");
        for (String column :
                new String[] {
                    "payee_user_id VARCHAR(32)",
                    "sandbox_app_id VARCHAR(32)",
                    "sandbox_seller_id VARCHAR(32)",
                    "transfer_state VARCHAR(16) DEFAULT 'NOT_STARTED' NOT NULL",
                    "transfer_error_code VARCHAR(64)",
                    "transfer_started_at TIMESTAMP",
                    "last_transfer_at TIMESTAMP",
                    "transfer_lease_until TIMESTAMP"
                }) ddl("ALTER TABLE wallet_withdrawal_order ADD COLUMN " + column);
        tables("V78__alipay_sandbox_withdrawal_transfers.sql", "wallet_withdrawal_transfer_log");
        tables("V79__consultation_message_reads.sql", "consultation_message_read");
        ddl("ALTER TABLE wallet_sandbox_account ADD COLUMN frozen_cents BIGINT DEFAULT 0 NOT NULL");
        ddl(
                "ALTER TABLE wallet_sandbox_account ADD COLUMN income_available_cents BIGINT"
                        + " DEFAULT 0 NOT NULL");
        ddl(
                "ALTER TABLE wallet_sandbox_account ADD CONSTRAINT check_income_available"
                        + " CHECK(income_available_cents>=0 AND"
                        + " income_available_cents<=balance_cents)");
        tables("V1__init_schema.sql", "media_file");
        ddl("ALTER TABLE media_file DROP CONSTRAINT chk_mf_biz");
        ddl(
                "ALTER TABLE media_file ADD CONSTRAINT chk_mf_biz CHECK(biz_type IN"
                        + " (1,2,3,4,5,6,7,8,9))");
        service = context.getBean(ConsultationService.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
        if (context != null) context.close();
    }

    void ddl(String sql) throws Exception {
        try (var st = schemaConnection.createStatement()) {
            st.execute(sql);
        }
    }

    void tables(String file, String... names) throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/" + file)) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String name : names) {
            int start = sql.indexOf("CREATE TABLE " + name);
            ddl(sql.substring(start, sql.indexOf(") ENGINE=", start) + 1).replace(") STORED", ")"));
        }
    }

    @Test
    void healthTopicsAreDynamicAndFilteringHappensBeforePagination() {
        assertEquals(30, service.topics().size());
        assertEquals("GLUCOSE", service.topics().getFirst().getCode());
        assertEquals(4, service.topics().stream().map(t -> t.getGroupName()).distinct().count());
        var old = publish();
        var form =
                new PublishRequest(
                        "餐后血糖问题测试", "POST_MEAL", "这是一份餐后血糖问题的测试描述。", "", "", true, "new_topic_001");
        var created = service.publish(1, form);
        assertEquals("餐后血糖", created.topicName());
        assertEquals("血糖波动", service.detail(1, old.id()).topicName());
        var page = new PageQuery();
        page.setPageSize(1);
        var result = service.list(3, false, false, "POST_MEAL", page);
        assertEquals(1, result.getTotal());
        assertEquals(created.id(), result.getRecords().getFirst().id());
        assertNull(result.getRecords().getFirst().symptoms());
        jdbc.update("UPDATE consultation_topic SET enabled=0 WHERE code='POST_MEAL'");
        assertFalse(service.topics().stream().anyMatch(t -> t.getCode().equals("POST_MEAL")));
        // 停用后历史名称及原请求重放仍有效，新请求必须重新选分类。
        assertEquals("餐后血糖", service.detail(1, created.id()).topicName());
        assertEquals(created.id(), service.publish(1, form).id());
        assertThrows(
                BizException.class,
                () ->
                        service.publish(
                                1,
                                new PublishRequest(
                                        "新发布测试标题",
                                        "POST_MEAL",
                                        "这是一份尚未发布的健康问题测试描述。",
                                        "",
                                        "",
                                        true,
                                        "new_topic_002")));
        assertThrows(BizException.class, () -> service.list(1, false, false, "POST_MEAL", page));
        assertThrows(BizException.class, () -> service.list(1, false, false, "' OR 1=1", page));
        assertThrows(
                BizException.class,
                () ->
                        service.publish(
                                1,
                                new PublishRequest(
                                        "新发布测试标题",
                                        "UNKNOWN",
                                        "这是一份尚未发布的健康问题测试描述。",
                                        "",
                                        "",
                                        true,
                                        "new_topic_003")));
        assertEquals(
                2, jdbc.queryForObject("SELECT COUNT(*) FROM consultation_request", Integer.class));
        jdbc.update(
                "INSERT INTO consultation_topic(code,name,group_name,sort_no)"
                    + " VALUES('NEW_TEST','动态测试分类','生活管理',999)");
        var dynamic =
                service.publish(
                        1,
                        new PublishRequest(
                                "动态分类测试标题",
                                "NEW_TEST",
                                "这是一份动态分类的问题测试描述。",
                                "",
                                "",
                                true,
                                "new_topic_004"));
        assertEquals("动态测试分类", dynamic.topicName());
    }

    void approve(long id) {
        var d =
                service.apply(
                        id,
                        new DoctorApplication("医生" + id, "测试机构", "内分泌", "TEST-" + id, "测试资质材料"));
        service.review(99, id, new DoctorReview("APPROVED", "已核验", d.getVersion()));
    }

    CaseView publish() {
        return service.publish(
                1,
                new PublishRequest(
                        "最近的餐后血糖变化",
                        "GLUCOSE",
                        "这是一份测试中使用的健康情况描述。",
                        "测试血糖详情",
                        "测试用药详情",
                        true,
                        "publish_request_1"));
    }

    ReportRequest reportForm() {
        return new ReportRequest("测试评估内容", "测试建议内容", "测试复查内容", "report_request_1");
    }

    long readyReport() {
        approve(2);
        var c = publish();
        service.claim(2, c.id());
        return service.report(2, c.id(), reportForm()).report().id();
    }

    String order(long report) {
        return service.createOrder(1, new OrderRequest(report, "order_request_1"));
    }

    long balance(long id) {
        return jdbc.queryForObject(
                "SELECT balance_cents FROM wallet_sandbox_account WHERE user_id=?", Long.class, id);
    }

    int entries() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM consultation_wallet_entry", Integer.class);
    }

    @Test
    void consultationInboxIsPrivateAndReadCursorNeverConsumesLaterMessagesOrRewinds() {
        approve(2);
        approve(3);
        var c = publish();
        service.claim(2,c.id());
        var incoming =
                service.sendMessage(
                        2,
                        c.id(),
                        new com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos
                                .Send("请补充测量时间", "chat_notice_0001"));
        var mapper=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(com.threadfit.glucopilot.consultation.mapper.ConsultationConversationMapper.class);
        var inbox=new com.threadfit.glucopilot.consultation.service.ConsultationConversationService(mapper);
        var page=new PageQuery();
        assertEquals(1,inbox.list(1,page).getTotal());
        assertEquals(1,inbox.list(1,page).getRecords().getFirst().getUnreadCount());
        assertEquals(0,inbox.list(2,page).getRecords().getFirst().getUnreadCount());
        assertEquals(0,inbox.list(3,page).getTotal());
        assertThrows(BizException.class,()->inbox.read(3,c.id(),incoming.getId()));
        var later =
                service.sendMessage(
                        2,
                        c.id(),
                        new com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos
                                .Send("稍后补充饮食情况", "chat_notice_0002"));
        inbox.read(1,c.id(),incoming.getId());
        assertEquals(1,inbox.list(1,page).getRecords().getFirst().getUnreadCount());
        inbox.read(1,c.id(),later.getId());
        inbox.read(1,c.id(),incoming.getId());
        assertEquals(0,inbox.list(1,page).getRecords().getFirst().getUnreadCount());
        var own =
                service.sendMessage(
                        1,
                        c.id(),
                        new com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos
                                .Send("已补充", "chat_notice_0003"));
        assertThrows(BizException.class,()->inbox.read(1,c.id(),own.getId()));
        jdbc.update("UPDATE consultation_doctor SET status='DISABLED' WHERE user_id=2");
        assertEquals(0,inbox.list(2,page).getTotal());
        assertThrows(BizException.class,()->inbox.read(2,c.id(),own.getId()));
        assertEquals(1,inbox.list(1,page).getTotal());
    }

    @Test
    void privatePhotosAreAtomicOwnedAndLimitedToThePatientAndAssignedDoctor() {
        jdbc.update(
                "INSERT INTO"
                    + " media_file(id,user_id,biz_type,storage_key,file_url,mime_type,size_bytes,bucket_name)"
                    + " VALUES(10,1,9,'9/test/1/a.png','private','image/png',3,'test'),(11,3,9,'9/test/3/a.png','private','image/png',3,'test')");
        var form =
                new PublishRequest(
                        "配图咨询测试问题",
                        "GLUCOSE",
                        "这是用于验证图片权限的一段测试描述。",
                        "",
                        "",
                        true,
                        "photos_request_01",
                        List.of(10L));
        var c = service.publish(1, form);
        assertEquals(1, service.images(1, c.id()).size());
        assertEquals(c.id(), service.publish(1, form).id());
        approve(2);
        approve(3);
        assertThrows(BizException.class, () -> service.images(2, c.id()));
        service.claim(2, c.id());
        assertEquals(1, service.images(2, c.id()).size());
        assertThrows(BizException.class, () -> service.images(3, c.id()));
        assertThrows(
                BizException.class,
                () ->
                        service.publish(
                                1,
                                new PublishRequest(
                                        form.title(),
                                        form.topic(),
                                        form.symptoms(),
                                        "",
                                        "",
                                        true,
                                        "photos_request_bad",
                                        List.of(11L))));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM consultation_request", Integer.class));
        var storage =
                org.mockito.Mockito.mock(
                        com.threadfit.glucopilot.file.storage.ObjectStorageService.class);
        org.mockito.Mockito.when(storage.provider())
                .thenReturn(com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider.MINIO);
        org.mockito.Mockito.when(storage.bucket()).thenReturn("test");
        org.mockito.Mockito.when(storage.getObject("9/test/1/a.png"))
                .thenReturn(new byte[] {1, 2, 3});
        var media =
                new com.threadfit.glucopilot.consultation.service.ConsultationMediaService(
                        service, context.getBean(ConsultationMapper.class), storage);
        assertEquals(3, media.read(2, c.id(), 10).bytes().length);
        org.mockito.Mockito.clearInvocations(storage);
        assertThrows(BizException.class, () -> media.read(3, c.id(), 10));
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .getObject(org.mockito.ArgumentMatchers.anyString());
        assertThrows(BizException.class, () -> media.read(2, null, 10));
    }

    @Test
    void patientDoctorConversationIsPrivateIdempotentPagedAndDoesNotPublishAReport() {
        approve(2);
        approve(3);
        var c = publish();
        var first =
                new com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.Send(
                        "请问需要补充哪些情况？", "consult_reply_001");
        assertThrows(BizException.class, () -> service.sendMessage(1, c.id(), first));
        service.claim(2, c.id());
        var saved = service.sendMessage(1, c.id(), first);
        assertEquals(saved.getId(), service.sendMessage(1, c.id(), first).getId());
        assertThrows(BizException.class, () -> service.messages(3, c.id(), null, null, 30));
        for (int i = 0; i < 4; i++)
            service.sendMessage(
                    2,
                    c.id(),
                    new com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.Send(
                            "医生回复" + i, "doctor_reply_00" + i));
        var latest = service.messages(1, c.id(), null, null, 2);
        assertEquals(2, latest.records().size());
        assertTrue(latest.hasMore());
        assertFalse(latest.records().getFirst().isMine());
        var older = service.messages(1, c.id(), null, latest.nextBeforeId(), 10);
        assertEquals(3, older.records().size());
        assertTrue(older.records().getFirst().isMine());
        assertEquals("PATIENT", older.records().getFirst().getSenderRole());
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM consultation_report", Integer.class));
        assertThrows(
                BizException.class,
                () ->
                        service.sendMessage(
                                1,
                                c.id(),
                                new com.threadfit.glucopilot.consultation.dto
                                        .ConsultationConversationDtos.Send(
                                        "更改的内容", first.clientRequestId())));
        jdbc.update("UPDATE consultation_request SET status='CLOSED' WHERE id=?", c.id());
        assertFalse(service.messages(1, c.id(), null, null, 30).canSend());
        assertThrows(
                BizException.class,
                () ->
                        service.sendMessage(
                                1,
                                c.id(),
                                new com.threadfit.glucopilot.consultation.dto
                                        .ConsultationConversationDtos.Send(
                                        "新消息", "closed_reply_001")));
    }

    com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService withdrawals() {
        return context.getBean(
                com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService.class);
    }

    void earn() {
        long report = readyReport();
        jdbc.update("INSERT INTO wallet_sandbox_account(user_id,balance_cents) VALUES(1,1000)");
        service.pay(1, order(report));
    }

    com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.Apply withdrawForm(
            int amount, String key) {
        return new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.Apply(
                amount, key, "2088000000000002");
    }

    @Test
    void withdrawalFreezesOnlyEarnedIncomeAndRejectRestoresExactlyOnce() {
        earn();
        var w = withdrawals();
        assertEquals(100, w.summary(2).availableCents());
        // 充值即使增加可用余额，也不会增加可提现收入。
        jdbc.update("UPDATE wallet_sandbox_account SET balance_cents=1100 WHERE user_id=2");
        assertEquals(100, w.summary(2).availableCents());
        var order = w.apply(2, withdrawForm(60, "withdraw_freeze_1"));
        assertEquals(1040, balance(2));
        assertEquals(60, w.summary(2).frozenCents());
        assertEquals(
                order.getOrderNo(), w.apply(2, withdrawForm(60, "withdraw_freeze_1")).getOrderNo());
        assertThrows(BizException.class, () -> w.apply(2, withdrawForm(61, "withdraw_freeze_1")));
        w.review(
                99,
                order.getOrderNo(),
                new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.Review(
                        "REJECTED", "测试拒绝", 0L));
        assertEquals(1100, balance(2));
        assertEquals(0, w.summary(2).frozenCents());
        assertThrows(
                BizException.class,
                () ->
                        w.review(
                                98,
                                order.getOrderNo(),
                                new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos
                                        .Review("APPROVED", "旧版本", 0L)));
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_withdrawal_event", Integer.class));
    }

    @Test
    void approvedWithdrawalUsesConfiguredBusinessDaysAndPayoutIsIdempotent() {
        earn();
        var w = withdrawals();
        var monday = java.time.LocalDate.of(2026, 11, 16);
        assertEquals(monday.plusDays(7), w.fifthBusinessDay(monday));
        w.calendar(
                99,
                new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.CalendarDay(
                        monday.plusDays(1), false, "测试休息日"));
        assertEquals(monday.plusDays(8), w.fifthBusinessDay(monday));
        w.calendar(
                99,
                new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.CalendarDay(
                        monday.plusDays(5), true, "测试调休"));
        assertEquals(monday.plusDays(7), w.fifthBusinessDay(monday));
        var order = w.apply(2, withdrawForm(50, "withdraw_payout_1"));
        var approved =
                w.review(
                        99,
                        order.getOrderNo(),
                        new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.Review(
                                "APPROVED", "测试通过", 0L));
        assertEquals(w.fifthBusinessDay(w.today()), approved.getExpectedPayoutDate());
        var transfers = context.getBean(WithdrawalTransferService.class);
        var gateway = context.getBean(SandboxTransferGateway.class);
        assertThrows(BizException.class, () -> transfers.payout(order.getOrderNo(), 99L));
        verifyNoInteractions(gateway);
        jdbc.update(
                "UPDATE wallet_withdrawal_order SET expected_payout_date=? WHERE order_no=?",
                w.today(),
                order.getOrderNo());
        when(gateway.query(any()))
                .thenReturn(
                        null,
                        new SandboxTransferGateway.Proof(
                                order.getOrderNo(),
                                "ALIPAY_TRANSFER_1",
                                "FUND_1",
                                "SUCCESS",
                                new java.math.BigDecimal("0.50"),
                                "2088000000000002",
                                null));
        var paid = transfers.payout(order.getOrderNo(), 99L);
        assertEquals("COMPLETED", paid.getStatus());
        assertEquals("ALIPAY_TRANSFER_1", paid.getPayoutReference());
        assertEquals("SUCCESS", paid.getTransferState());
        assertEquals(
                paid.getPayoutReference(),
                transfers.payout(order.getOrderNo(), 99L).getPayoutReference());
        verify(gateway, times(1)).transfer(any());
        assertEquals(50, balance(2));
        assertEquals(0, w.summary(2).frozenCents());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wallet_withdrawal_event WHERE event_type='PAYOUT'",
                        Integer.class));
    }

    private String dueTransfer() {
        earn();
        var order = withdrawals().apply(2, withdrawForm(50, "transfer_fixture_001"));
        withdrawals()
                .review(
                        99,
                        order.getOrderNo(),
                        new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.Review(
                                "APPROVED", "沙箱审核", 0L));
        jdbc.update(
                "UPDATE wallet_withdrawal_order SET expected_payout_date=? WHERE order_no=?",
                withdrawals().today(),
                order.getOrderNo());
        return order.getOrderNo();
    }

    private SandboxTransferGateway.Proof transferProof(
            String no, String status, String amount, String receiver) {
        return new SandboxTransferGateway.Proof(
                no,
                "ALIPAY_TRANSFER_1",
                "FUND_1",
                status,
                amount == null ? null : new java.math.BigDecimal(amount),
                receiver,
                "TEST_FAILURE");
    }

    private void retryTransfer(String no) {
        jdbc.update(
                "UPDATE wallet_withdrawal_order SET"
                    + " last_transfer_at=TIMESTAMPADD(SECOND,-6,CURRENT_TIMESTAMP),transfer_lease_until=NULL"
                    + " WHERE order_no=?",
                no);
    }

    @Test
    void uncertainTransferIsNotPaidOrRefundedAndLaterQuerySettlesWithoutSecondSend() {
        String no = dueTransfer();
        var gateway = context.getBean(SandboxTransferGateway.class);
        var transfers = context.getBean(WithdrawalTransferService.class);
        when(gateway.query(any())).thenReturn(null);
        doThrow(
                        new BizException(
                                com.threadfit.glucopilot.common.api.ErrorCode
                                        .PAYMENT_UPSTREAM_ERROR,
                                "safe",
                                Map.of("providerSubCode", "SDK_TIMEOUT")))
                .when(gateway)
                .transfer(any());
        var pending = transfers.payout(no, 99L);
        assertEquals("APPROVED", pending.getStatus());
        assertEquals("UNKNOWN", pending.getTransferState());
        assertEquals("SDK_TIMEOUT", pending.getTransferErrorCode());
        assertEquals(50, withdrawals().summary(2).frozenCents());
        assertEquals(50, balance(2));
        retryTransfer(no);
        when(gateway.query(any()))
                .thenReturn(transferProof(no, "SUCCESS", "0.50", "2088000000000002"));
        transfers.refreshOwn(2);
        assertEquals(0, withdrawals().summary(2).frozenCents());
        assertEquals(
                "COMPLETED", context.getBean(WithdrawalTransferStore.class).find(no).getStatus());
        verify(gateway, times(1)).transfer(any());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wallet_withdrawal_event WHERE event_type='PAYOUT'",
                        Integer.class));
    }

    @Test
    void signedFailedTransferReturnsFrozenIncomeOnceAndOtherUsersRefreshDoesNothing() {
        String no = dueTransfer();
        var gateway = context.getBean(SandboxTransferGateway.class);
        var transfers = context.getBean(WithdrawalTransferService.class);
        transfers.refreshOwn(1);
        verifyNoInteractions(gateway);
        when(gateway.query(any())).thenReturn(transferProof(no, "FAIL", null, "2088000000000002"));
        assertEquals("REJECTED", transfers.payout(no, 99L).getStatus());
        assertEquals(100, balance(2));
        assertEquals(100, withdrawals().summary(2).availableCents());
        assertEquals(0, withdrawals().summary(2).frozenCents());
        transfers.payout(no, 99L);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wallet_withdrawal_event WHERE event_type='REJECT'",
                        Integer.class));
        verify(gateway, never()).transfer(any());
    }

    @Test
    void mismatchedProofAndMerchantCannotReleaseFrozenMoney() {
        String no = dueTransfer();
        var gateway = context.getBean(SandboxTransferGateway.class);
        var transfers = context.getBean(WithdrawalTransferService.class);
        for (var proof :
                List.of(
                        transferProof("OTHER_ORDER", "SUCCESS", "0.50", "2088000000000002"),
                        transferProof(no, "SUCCESS", "0.51", "2088000000000002"),
                        transferProof(no, "SUCCESS", "0.50", "2088000000000003"))) {
            retryTransfer(no);
            when(gateway.query(any())).thenReturn(proof);
            assertEquals("UNKNOWN", transfers.payout(no, 99L).getTransferState());
            assertEquals(50, withdrawals().summary(2).frozenCents());
        }
        context.getBean(AlipaySandboxProperties.class).setAppId("2026000000000009");
        retryTransfer(no);
        assertThrows(BizException.class, () -> transfers.payout(no, 99L));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wallet_withdrawal_event WHERE event_type='PAYOUT'",
                        Integer.class));
    }

    @Test
    void payeeBindingIsOwnerOnlyAndCannotChangeAfterTransferStarted() {
        String no = dueTransfer();
        var form =
                new com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.Recipient(
                        "2088000000000003");
        assertThrows(BizException.class, () -> withdrawals().recipient(1, no, form));
        assertEquals("2088000000000003", withdrawals().recipient(2, no, form).getPayeeUserId());
        assertThrows(
                BizException.class,
                () ->
                        withdrawals()
                                .recipient(
                                        2,
                                        no,
                                        new com.threadfit.glucopilot.wallet.withdrawal.dto
                                                .WithdrawalDtos.Recipient("2088000000000001")));
        context.getBean(WithdrawalTransferService.class).payout(no, 99L);
        assertThrows(BizException.class, () -> withdrawals().recipient(2, no, form));
    }

    @Test
    void transferNetworkCallsHoldNoTransactionAndConcurrentClicksDoNotSendTwice() throws Exception {
        String no = dueTransfer();
        var gateway = context.getBean(SandboxTransferGateway.class);
        var transfers = context.getBean(WithdrawalTransferService.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.query(any()))
                .thenAnswer(
                        call -> {
                            assertFalse(
                                    org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive());
                            started.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return transferProof(no, "SUCCESS", "0.50", "2088000000000002");
                        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> transfers.payout(no, 99L));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("PROCESSING", transfers.payout(no, 98L).getTransferState());
            release.countDown();
            assertEquals("COMPLETED", first.get(5, TimeUnit.SECONDS).getStatus());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        verify(gateway, times(1)).query(any());
        verify(gateway, never()).transfer(any());
    }

    @Test
    void refreshingNotStartedOrderNeverSubmitsTransferOrSkipsBusinessDay() {
        String no = dueTransfer();
        jdbc.update(
                "UPDATE wallet_withdrawal_order SET expected_payout_date=? WHERE order_no=?",
                withdrawals().today().plusDays(5),
                no);
        var transfers = context.getBean(WithdrawalTransferService.class);
        var gateway = context.getBean(SandboxTransferGateway.class);
        transfers.refreshOwn(2);
        transfers.refresh(no, 99L);
        assertThrows(BizException.class, () -> transfers.payout(no, 99L));
        verifyNoInteractions(gateway);
        assertEquals(50, withdrawals().summary(2).frozenCents());
    }

    @Test
    void simultaneousWithdrawalsCannotFreezeTheSameIncomeTwice() throws Exception {
        earn();
        var w = withdrawals();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                final int key = i;
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        w.apply(2, withdrawForm(80, "withdraw_parallel_" + key));
                                        return true;
                                    } catch (BizException e) {
                                        return false;
                                    }
                                }));
            }
            gate.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(20, balance(2));
            assertEquals(80, w.summary(2).frozenCents());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM wallet_withdrawal_order", Integer.class));
        }
    }

    @Test
    void withdrawalLedgerFailureRollsBackOrderAndFreeze() {
        earn();
        var w = withdrawals();
        jdbc.execute(
                "ALTER TABLE wallet_withdrawal_event ADD CONSTRAINT reject_freeze_test"
                        + " CHECK(event_type<>'FREEZE')");
        assertThrows(
                RuntimeException.class, () -> w.apply(2, withdrawForm(50, "withdraw_rollback")));
        assertEquals(100, balance(2));
        assertEquals(0, w.summary(2).frozenCents());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_withdrawal_order", Integer.class));
    }

    @Test
    void spentIncomeCannotBeRestoredByLaterRecharge() {
        earn();
        approve(3);
        var c =
                service.publish(
                        2,
                        new PublishRequest(
                                "医生自己的咨询问题",
                                "DIET",
                                "这是用于验证收入消费后充值不会增加提现额度的测试。",
                                "",
                                "",
                                true,
                                "income_spend_case"));
        service.claim(3, c.id());
        var r =
                service.report(
                        3, c.id(), new ReportRequest("评估", "建议", "复查", "income_spend_report"));
        service.pay(
                2, service.createOrder(2, new OrderRequest(r.report().id(), "income_spend_order")));
        assertEquals(0, balance(2));
        assertEquals(0, withdrawals().summary(2).availableCents());
        // 模拟已验签的充值入账只增加总余额，不能改变已消费的收入额度。
        context.getBean(SandboxPaymentMapper.class).credit(2, 100);
        assertEquals(100, balance(2));
        assertEquals(0, withdrawals().summary(2).availableCents());
        assertThrows(
                BizException.class,
                () -> withdrawals().apply(2, withdrawForm(1, "not_earned_income")));
    }

    @Test
    void privacyApprovalAndIdempotencyAreEnforcedByServer() {
        var c = publish();
        assertEquals(c.id(), publish().id());
        assertTrue(service.detail(1, c.id()).detailsVisible());
        assertNull(service.detail(4, c.id()).symptoms());
        assertNull(
                service.list(4, false, false, null, new PageQuery())
                        .getRecords()
                        .getFirst()
                        .symptoms());
        assertThrows(BizException.class, () -> service.claim(2, c.id()));
        approve(2);
        assertTrue(service.claim(2, c.id()).detailsVisible());
        assertThrows(BizException.class, () -> service.claim(4, c.id()));
        var report = service.report(2, c.id(), reportForm()).report();
        assertEquals("UNLOCKED", report.access());
        assertEquals(report.id(), service.report(2, c.id(), reportForm()).report().id());
        assertEquals("LOCKED", service.reportDetail(1, report.id()).access());
        assertNull(service.reportDetail(1, report.id()).medicationAdvice());
        assertThrows(BizException.class, () -> service.reportDetail(4, report.id()));
        assertThrows(
                BizException.class,
                () -> service.createOrder(4, new OrderRequest(report.id(), "order_intruder_1")));
        assertThrows(
                BizException.class,
                () ->
                        service.report(
                                2,
                                c.id(),
                                new ReportRequest(
                                        "changed", "changed", "changed", "report_request_1")));
    }

    @Test
    void listAndDetailReadNicknameFromProfileWithoutRequiringAProfile() {
        var form =
                new PublishRequest(
                        "展示非匿名的公开昵称",
                        "GLUCOSE",
                        "这份描述用于核对公开昵称的来源。",
                        "",
                        "",
                        false,
                        "profile_request_1");
        var item = service.publish(1, form);
        assertEquals("patient", item.patientName());
        assertEquals("patient", service.detail(4, item.id()).patientName());
        assertEquals(
                "patient",
                service.list(4, false, false, "", new PageQuery())
                        .getRecords()
                        .getFirst()
                        .patientName());
        assertEquals(1, service.list(1, true, false, null, new PageQuery()).getTotal());
        jdbc.update("UPDATE user_profile SET is_deleted=1 WHERE user_id=1");
        assertEquals("糖友", service.detail(4, item.id()).patientName());
        assertEquals(1, service.list(4, false, false, null, new PageQuery()).getTotal());
        jdbc.update("DELETE FROM user_profile WHERE user_id=1");
        assertEquals(
                "糖友",
                service.list(4, false, false, "GLUCOSE", new PageQuery())
                        .getRecords()
                        .getFirst()
                        .patientName());
    }

    @Test
    void hideMineFiltersBeforePaginationAndDoesNotHideMyRecords() {
        service.publish(
                4,
                new PublishRequest(
                        "其他糖友的求助", "DIET", "用于检查分页前过滤自己的求助内容。", "", "", true, "hide_other_0001"));
        service.publish(
                1,
                new PublishRequest(
                        "自己的第一条求助", "DIET", "用于检查自己的求助不会占用分页名额。", "", "", true, "hide_self_00001"));
        service.publish(
                1,
                new PublishRequest(
                        "自己的第二条求助",
                        "GLUCOSE",
                        "用于检查同样的过滤条件也适用于分类和总数。",
                        "",
                        "",
                        false,
                        "hide_self_00002"));
        var page = new PageQuery();
        page.setPageSize(1);
        var all = service.list(1, false, false, false, null, page);
        assertEquals(3, all.getTotal());
        assertTrue(all.getRecords().getFirst().mine());
        var others = service.list(1, false, false, true, null, page);
        assertEquals(1, others.getTotal());
        assertEquals(1, others.getRecords().size());
        assertFalse(others.getRecords().getFirst().mine());
        assertEquals("其他糖友的求助", others.getRecords().getFirst().title());
        assertEquals(0, service.list(1, false, false, true, "GLUCOSE", page).getTotal());
        assertEquals(2, service.list(1, true, false, true, null, page).getTotal());
    }

    @Test
    void unclaimedCasesAreFilteredBeforePaginationAndCombineTopicAndHideMine() {
        approve(2);
        for (int i = 0; i < 8; i++) {
            long patient = i == 3 ? 2 : 1;
            String topic = i == 2 ? "DIET" : "GLUCOSE";
            var item =
                    service.publish(
                            patient,
                            new PublishRequest(
                                    "筛选测试求助" + i,
                                    topic,
                                    "仅用于未接诊筛选测试的说明内容。",
                                    "",
                                    "",
                                    false,
                                    "unclaimed_case_" + i));
            if (i >= 4) {
                String status =
                        switch (i) {
                            case 4 -> "IN_PROGRESS";
                            case 5 -> "REPORT_READY";
                            case 6 -> "CLOSED";
                            default -> "WAITING";
                        };
                jdbc.update(
                        "UPDATE consultation_request SET status=?,doctor_user_id=? WHERE id=?",
                        status,
                        i == 6 ? null : 3,
                        item.id());
            }
        }
        var all = service.list(2, false, false, false, true, null, new PageQuery());
        assertEquals(4, all.getTotal());
        assertEquals(4, all.getRecords().size());
        assertTrue(all.getRecords().stream().allMatch(row -> "WAITING".equals(row.status())));
        var page = new PageQuery();
        page.setPageSize(1);
        var first = service.list(2, false, false, true, true, "GLUCOSE", page);
        assertEquals(2, first.getTotal());
        assertEquals(1, first.getRecords().size());
        assertFalse(first.getRecords().getFirst().mine());
        page.setPageNum(2);
        var second = service.list(2, false, false, true, true, "GLUCOSE", page);
        assertEquals(2, second.getTotal());
        assertEquals(1, second.getRecords().size());
        assertNotEquals(first.getRecords().getFirst().id(), second.getRecords().getFirst().id());
        service.claim(2, first.getRecords().getFirst().id());
        page.setPageNum(1);
        assertEquals(1, service.list(2, false, false, true, true, "GLUCOSE", page).getTotal());
    }

    @Test
    void unclaimedFilterRequiresCurrentApprovedDoctorAndOnlyAppliesToSquare() {
        var page = new PageQuery();
        var ordinary =
                assertThrows(
                        BizException.class,
                        () -> service.list(1, false, false, false, true, null, page));
        assertEquals(
                com.threadfit.glucopilot.common.api.ErrorCode.FORBIDDEN, ordinary.getErrorCode());
        service.apply(3, new DoctorApplication("测试医生", "机构", "内分泌", "PENDING_TEST", "测试资质"));
        assertThrows(
                BizException.class, () -> service.list(3, false, false, false, true, null, page));
        approve(2);
        assertThrows(
                BizException.class, () -> service.list(2, true, false, false, true, null, page));
        assertThrows(
                BizException.class, () -> service.list(2, false, true, false, true, null, page));
        jdbc.update("UPDATE consultation_doctor SET status='DISABLED' WHERE user_id=2");
        assertThrows(
                BizException.class, () -> service.list(2, false, false, false, true, null, page));
        assertDoesNotThrow(() -> service.list(1, false, false, null, page));
    }

    @Test
    void squareStatusFiltersCombineOwnershipAndTopicBeforePagination() {
        String[] statuses = {"WAITING", "IN_PROGRESS", "REPORT_READY", "CLOSED"};
        for (String status : statuses) {
            for (long patient : new long[] {1, 3, 4}) {
                var item = service.publish(patient, new PublishRequest(
                        "广场状态筛选测试", "GLUCOSE", "用于验证状态归属和分页条件组合后的查询结果。", "", "",
                        false, "square_status_" + status + "_" + patient));
                jdbc.update("UPDATE consultation_request SET status=?,doctor_user_id=? WHERE id=?",
                        status, "WAITING".equals(status) ? null : 2L, item.id());
            }
        }
        var inconsistent = service.publish(4, new PublishRequest(
                "状态不一致测试求助", "GLUCOSE", "已绑定医生的记录不应显示为等待医生。", "", "",
                false, "square_inconsistent_001"));
        jdbc.update("UPDATE consultation_request SET doctor_user_id=2 WHERE id=?", inconsistent.id());
        var otherTopic = service.publish(3, new PublishRequest(
                "饮食分类测试求助", "DIET", "用于验证状态筛选还会同时限制分类。", "", "", false,
                "square_status_diet_001"));
        jdbc.update("UPDATE consultation_request SET status='REPORT_READY',doctor_user_id=2 WHERE id=?", otherTopic.id());
        for (String status : statuses) {
            var mine = service.list(1, true, false, false, false, status, "GLUCOSE", new PageQuery());
            assertEquals(1, mine.getTotal());
            assertTrue(mine.getRecords().getFirst().mine());
            var others = service.list(1, false, false, true, false, status, "GLUCOSE", new PageQuery());
            assertEquals(2, others.getTotal());
            assertTrue(others.getRecords().stream().allMatch(row -> status.equals(row.status()) && !row.mine()));
            assertTrue(others.getRecords().stream().noneMatch(row -> row.detailsVisible()));
        }
        var page = new PageQuery(); page.setPageSize(1);
        var first = service.list(1, false, false, true, false, "REPORT_READY", "GLUCOSE", page);
        assertEquals(2, first.getTotal()); assertEquals(1, first.getRecords().size());
        page.setPageNum(2);
        var second = service.list(1, false, false, true, false, "REPORT_READY", "GLUCOSE", page);
        assertEquals(2, second.getTotal());
        assertNotEquals(first.getRecords().getFirst().id(), second.getRecords().getFirst().id());
        assertEquals(1, service.list(1, false, false, true, false, "REPORT_READY", "DIET", new PageQuery()).getTotal());
    }

    @Test
    void squareStatusRejectsInvalidStatesAndConflictingLegacyFilters() {
        for (String state : new String[] {"report_ready", "UNKNOWN", "WAITING' OR 1=1"}) {
            var error = assertThrows(BizException.class,
                    () -> service.list(1, false, false, false, false, state, null, new PageQuery()));
            assertEquals(com.threadfit.glucopilot.common.api.ErrorCode.BAD_REQUEST, error.getErrorCode());
        }
        approve(2);
        var error = assertThrows(BizException.class,
                () -> service.list(2, false, false, false, true, "REPORT_READY", null, new PageQuery()));
        assertEquals(com.threadfit.glucopilot.common.api.ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertDoesNotThrow(() -> service.list(1, false, false, false, false, "WAITING", null, new PageQuery()));
    }

    @Test
    void insufficientFundsAndInactiveWalletNeverUnlockOrCredit() {
        long report = readyReport();
        String no = order(report);
        assertThrows(BizException.class, () -> service.pay(1, no));
        assertEquals(0, entries());
        assertEquals("LOCKED", service.reportDetail(1, report).access());
        jdbc.update("INSERT INTO wallet_sandbox_account(user_id,balance_cents) VALUES(1,1000)");
        jdbc.update("UPDATE user_account SET wallet_activated_at=NULL WHERE id=2");
        assertThrows(BizException.class, () -> service.pay(1, no));
        assertEquals(1000, balance(1));
        assertEquals(0, entries());
    }

    @Test
    void concurrentPaymentTransfersOnlyOnceAndConservesMoney() throws Exception {
        long report = readyReport();
        String no = order(report);
        assertEquals(no, service.createOrder(1, new OrderRequest(report, "order_request_2")));
        jdbc.update(
                "INSERT INTO wallet_sandbox_account(user_id,balance_cents) VALUES(1,1000),(2,25)");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                gate.await();
                                service.pay(1, no);
                                return true;
                            });
            var second =
                    pool.submit(
                            () -> {
                                gate.await();
                                service.pay(1, no);
                                return true;
                            });
            gate.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS));
            assertTrue(second.get(15, TimeUnit.SECONDS));
        }
        service.pay(1, no);
        assertEquals(900, balance(1));
        assertEquals(125, balance(2));
        assertEquals(2, entries());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT SUM(amount_cents) FROM consultation_wallet_entry", Integer.class));
        assertEquals("UNLOCKED", service.reportDetail(1, report).access());
        assertThrows(BizException.class, () -> service.pay(4, no));
    }

    @Test
    void differentOrdersCannotSpendTheSameLastOneYuan() throws Exception {
        long firstReport = readyReport();
        var secondCase =
                service.publish(
                        1,
                        new PublishRequest(
                                "第二份问诊请求标题",
                                "DIET",
                                "第二份问诊的测试健康描述内容。",
                                "",
                                "",
                                true,
                                "publish_request_2"));
        service.claim(2, secondCase.id());
        long secondReport =
                service.report(
                                2,
                                secondCase.id(),
                                new ReportRequest("第二份评估", "第二份建议", "第二份复查", "report_request_2"))
                        .report()
                        .id();
        String firstOrder = order(firstReport);
        String secondOrder =
                service.createOrder(1, new OrderRequest(secondReport, "order_request_2"));
        jdbc.update(
                "INSERT INTO wallet_sandbox_account(user_id,balance_cents) VALUES(1,100),(2,0)");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<Boolean>>();
            for (String no : List.of(firstOrder, secondOrder)) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        service.pay(1, no);
                                        return true;
                                    } catch (BizException expected) {
                                        return false;
                                    }
                                }));
            }
            gate.countDown();
            int paid = 0;
            for (var job : jobs) if (job.get(15, TimeUnit.SECONDS)) paid++;
            assertEquals(1, paid);
        }
        assertEquals(0, balance(1));
        assertEquals(100, balance(2));
        assertEquals(2, entries());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM consultation_order WHERE status='PAID'",
                        Integer.class));
    }

    @Test
    void ledgerFailureRollsBackBothBalancesAndAuthorization() {
        long report = readyReport();
        String no = order(report);
        jdbc.update(
                "INSERT INTO wallet_sandbox_account(user_id,balance_cents) VALUES(1,1000),(2,0)");
        jdbc.update(
                "INSERT INTO"
                    + " consultation_wallet_entry(order_no,user_id,amount_cents,balance_after_cents)"
                    + " VALUES(?,2,100,100)",
                no);
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.pay(1, no));
        assertEquals(1000, balance(1));
        assertEquals(0, balance(2));
        assertEquals("LOCKED", service.reportDetail(1, report).access());
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT status FROM consultation_order WHERE order_no=?",
                        String.class,
                        no));
    }

    @Test
    void concurrentDoctorsCannotClaimTheSameCase() throws Exception {
        approve(2);
        approve(3);
        long id = publish().id();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<Boolean>>();
            for (long doctor : new long[] {2, 3})
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        service.claim(doctor, id);
                                        return true;
                                    } catch (BizException expected) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int won = 0;
            for (var job : jobs) if (job.get(15, TimeUnit.SECONDS)) won++;
            assertEquals(1, won);
        }
    }

    @Test
    void reviewVersionAndDoctorRevocationBlockStaleActions() {
        long report = readyReport();
        var doctor = service.doctor(2);
        service.review(99, 2, new DoctorReview("DISABLED", "已停用", doctor.getVersion()));
        assertThrows(
                BizException.class,
                () ->
                        service.review(
                                99, 2, new DoctorReview("APPROVED", "旧版本", doctor.getVersion())));
        assertEquals("LOCKED", service.reportDetail(2, report).access());
        assertThrows(BizException.class, () -> order(report));
    }
}
