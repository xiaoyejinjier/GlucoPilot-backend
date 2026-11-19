package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Proof;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.payment.service.*;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;

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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

class SandboxRechargeTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SandboxRechargeService orders;
    private SandboxPaymentService payments;
    private SandboxGateway gateway;
    private java.sql.Connection schemaConnection;

    @Configuration
    @EnableTransactionManagement
    static class Beans {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:sandbox_"
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
            config.addMapper(SandboxPaymentMapper.class);
            return new MybatisSqlSessionFactoryBuilder().build(config);
        }

        @Bean
        SandboxPaymentMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(SandboxPaymentMapper.class);
        }

        @Bean
        AlipaySandboxProperties properties() {
            var properties = new AlipaySandboxProperties();
            properties.setEnabled(true);
            properties.setAppId("2026000000000001");
            properties.setSellerId("2088000000000001");
            properties.setPrivateKey("test-key-never-used-for-network");
            properties.setPublicKey("test-key-never-used-for-network");
            return properties;
        }

        @Bean
        SandboxGateway gateway() {
            return mock(SandboxGateway.class);
        }

        @Bean
        SandboxRechargeService orders(
                SandboxPaymentMapper mapper, AlipaySandboxProperties properties) {
            return new SandboxRechargeService(mapper, properties);
        }

        @Bean
        SandboxPaymentService payments(
                SandboxRechargeService orders,
                SandboxPaymentMapper mapper,
                SandboxGateway gateway) {
            return new SandboxPaymentService(orders, mapper, gateway);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Beans.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        // H2的IN检查表达式保留建表连接的比较器；测试期间保留该连接，不移除生产约束。
        schemaConnection = context.getBean(DataSource.class).getConnection();
        ddl(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT DEFAULT"
                        + " 0,account_status INT DEFAULT 1,wallet_activated_at TIMESTAMP)");
        jdbc.update(
                "INSERT INTO user_account(id,wallet_activated_at)"
                        + " VALUES(1,CURRENT_TIMESTAMP),(2,NULL)");
        String initial = resource("V64__wallet_recharge_and_consultation_orders.sql");
        int start = initial.indexOf("CREATE TABLE wallet_recharge_order");
        try (var ddl = schemaConnection.createStatement()) {
            ddl.execute(initial.substring(start, initial.indexOf(") ENGINE=", start) + 1));
        }
        String schema = resource("V66__alipay_sandbox_recharge.sql");
        for (String table : new String[] {"wallet_sandbox_account", "wallet_sandbox_ledger"}) {
            start = schema.indexOf("CREATE TABLE " + table);
            ddl(schema.substring(start, schema.indexOf(") ENGINE=", start) + 1));
        }
        for (String column :
                new String[] {
                    "sandbox_app_id VARCHAR(32)",
                    "sandbox_seller_id VARCHAR(32)",
                    "qr_code VARCHAR(2048)",
                    "expires_at TIMESTAMP",
                    "last_query_at TIMESTAMP",
                    "payment_mode VARCHAR(8) NOT NULL DEFAULT 'QR'"
                }) ddl("ALTER TABLE wallet_recharge_order ADD " + column);
        orders = context.getBean(SandboxRechargeService.class);
        payments = context.getBean(SandboxPaymentService.class);
        gateway = context.getBean(SandboxGateway.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
        context.close();
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void ddl(String sql) throws Exception {
        try (var statement = schemaConnection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String create(String key) {
        return orders.create(1, new WalletDtos.RechargeRequest(1000, key)).getOrderNo();
    }

    private Proof paid(String orderNo, String trade) {
        return new Proof(
                orderNo,
                trade,
                "TRADE_SUCCESS",
                new BigDecimal("10.00"),
                "2026000000000001",
                "2088000000000001");
    }

    private long balance() {
        return Optional.ofNullable(
                        jdbc.queryForObject(
                                "SELECT SUM(balance_cents) FROM wallet_sandbox_account",
                                Long.class))
                .orElse(0L);
    }

    @Test
    void idempotentCreationDoesNotChargeAndRequiresWalletOwnership() {
        String no = create("request_001");
        assertEquals(no, create("request_001"));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recharge_order", Integer.class));
        assertEquals(0, balance());
        assertThrows(
                BizException.class,
                () -> orders.create(1, new WalletDtos.RechargeRequest(2000, "request_001")));
        assertThrows(
                BizException.class,
                () -> orders.create(2, new WalletDtos.RechargeRequest(1000, "request_002")));
        assertThrows(BizException.class, () -> orders.owned(2, no));
    }

    @Test
    void pageOrdersKeepTheirModeAndGenerateLinksWithoutQrOrCrediting() {
        var request = new WalletDtos.RechargeRequest(1000, "page_request_001", "PAGE");
        var order = orders.create(1, request);
        String no = order.getOrderNo();
        assertEquals("PAGE", order.getPaymentMode());
        assertEquals(no, orders.create(1, request).getOrderNo());
        assertThrows(
                BizException.class,
                () ->
                        orders.create(
                                1, new WalletDtos.RechargeRequest(1000, "page_request_001", "QR")));
        assertThrows(BizException.class, () -> payments.pay(2, no));
        when(gateway.pagePay(any())).thenReturn("https://sandbox.example.test/payment");
        var payment = payments.pay(1, no);
        assertEquals("PAGE", payment.paymentMode());
        assertEquals("https://sandbox.example.test/payment", payment.pageUrl());
        assertNull(payment.qrCode());
        assertNull(payment.pngBase64());
        assertEquals(0, balance());
        payments.pay(1, no);
        verify(gateway, times(2)).pagePay(any());
        verify(gateway, never()).precreate(any());
        orders.settle(paid(no, "page_trade_001"));
        orders.settle(paid(no, "page_trade_001"));
        assertEquals(1000, balance());
        var paid = payments.pay(1, no);
        assertEquals("PAID", paid.status());
        assertEquals("PAGE", paid.paymentMode());
        assertNull(paid.pageUrl());
        verify(gateway, times(2)).pagePay(any());
    }

    @Test
    void expiredClosedAndInvalidModeCannotGeneratePagePayment() {
        var order =
                orders.create(1, new WalletDtos.RechargeRequest(1000, "page_expired_001", "PAGE"));
        jdbc.update(
                "UPDATE wallet_recharge_order SET expires_at=TIMESTAMP '2020-01-01 00:00:00' WHERE"
                    + " order_no=?",
                order.getOrderNo());
        assertThrows(BizException.class, () -> payments.pay(1, order.getOrderNo()));
        jdbc.update(
                "UPDATE wallet_recharge_order SET status='CLOSED' WHERE order_no=?",
                order.getOrderNo());
        assertNull(payments.pay(1, order.getOrderNo()).pageUrl());
        assertThrows(
                BizException.class,
                () ->
                        orders.create(
                                1,
                                new WalletDtos.RechargeRequest(1000, "page_invalid_001", "OTHER")));
        verifyNoInteractions(gateway);
    }

    @Test
    void signedSuccessCreditsExactlyOnceAndConflictingProofsDoNotCredit() {
        String no = create("request_001");
        assertThrows(
                BizException.class,
                () ->
                        orders.settle(
                                new Proof(
                                        no,
                                        "trade_001",
                                        "TRADE_SUCCESS",
                                        new BigDecimal("9.00"),
                                        "2026000000000001",
                                        "2088000000000001")));
        assertThrows(
                BizException.class,
                () ->
                        orders.settle(
                                new Proof(
                                        no,
                                        "trade_001",
                                        "TRADE_SUCCESS",
                                        new BigDecimal("10.00"),
                                        "2026000000000001",
                                        "2088000000000002")));
        assertEquals(0, balance());
        orders.settle(paid(no, "trade_001"));
        orders.settle(paid(no, "trade_001"));
        assertEquals(1000, balance());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_sandbox_ledger", Integer.class));
        assertEquals("PAID", orders.owned(1, no).getStatus());
        assertThrows(BizException.class, () -> orders.settle(paid(no, "different_trade")));
    }

    @Test
    void concurrentNotificationsAndQueriesSerializeWithoutDoubleCredit() throws Exception {
        String no = create("request_001");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var one =
                    executor.submit(
                            () -> {
                                start.await();
                                orders.settle(paid(no, "trade_001"));
                                return true;
                            });
            var two =
                    executor.submit(
                            () -> {
                                start.await();
                                orders.settle(paid(no, "trade_001"));
                                return true;
                            });
            start.countDown();
            assertTrue(one.get(10, TimeUnit.SECONDS));
            assertTrue(two.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1000, balance());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_sandbox_ledger", Integer.class));
    }

    @Test
    void duplicateChannelTradeRollsBackAllNewOrderAndBalanceChanges() {
        String first = create("request_001"), second = create("request_002");
        orders.settle(paid(first, "trade_001"));
        assertThrows(RuntimeException.class, () -> orders.settle(paid(second, "trade_001")));
        assertEquals(1000, balance());
        assertEquals("PENDING", orders.owned(1, second).getStatus());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_sandbox_ledger", Integer.class));
    }

    @Test
    void unsignedNotificationCannotChangeOrdersAndQrGenerationIsNotPayment() {
        String no = create("request_001");
        assertFalse(
                payments.notify(
                        Map.of(
                                "out_trade_no",
                                no,
                                "trade_status",
                                "TRADE_SUCCESS",
                                "total_amount",
                                "10.00")));
        when(gateway.precreate(any())).thenReturn("https://qr.alipay.com/test-only");
        var result = payments.pay(1, no);
        assertNotNull(result.pngBase64());
        assertEquals("PENDING", result.status());
        assertEquals(0, balance());
        payments.pay(1, no);
        verify(gateway, times(1)).precreate(any());
        assertThrows(BizException.class, () -> payments.pay(2, no));
    }

    @Test
    void successfulQueryWithWaitingStatusDoesNotCreditWallet() {
        String no = create("waiting_query_001");
        when(gateway.query(any()))
                .thenReturn(
                        new Proof(
                                no,
                                "trade_waiting",
                                "WAIT_BUYER_PAY",
                                new BigDecimal("10.00"),
                                "2026000000000001",
                                "2088000000000001"));
        payments.refresh(1, no);
        assertEquals("PENDING", orders.owned(1, no).getStatus());
        assertEquals(0, balance());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_sandbox_ledger", Integer.class));
        assertNull(orders.owned(1, no).getProviderTradeNo());
    }

    @Test
    void activeQueryIsRateLimitedAndConfirmsLatePaymentsAfterQrExpiry() {
        String no = create("request_001");
        jdbc.update(
                "UPDATE wallet_recharge_order SET"
                        + " expires_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP) WHERE order_no=?",
                no);
        assertThrows(BizException.class, () -> payments.pay(1, no));
        when(gateway.query(any())).thenReturn(null);
        payments.refresh(1, no);
        payments.refresh(1, no);
        verify(gateway, times(1)).query(any());
        jdbc.update("UPDATE wallet_recharge_order SET last_query_at=NULL WHERE order_no=?", no);
        when(gateway.query(any())).thenReturn(paid(no, "trade_001"));
        payments.refresh(1, no);
        assertEquals(1000, balance());
        assertEquals("PAID", orders.owned(1, no).getStatus());
    }
}
