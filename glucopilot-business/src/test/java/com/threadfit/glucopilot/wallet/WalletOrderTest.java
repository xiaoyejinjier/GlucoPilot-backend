package com.threadfit.glucopilot.wallet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.consultation.controller.ConsultationController;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.controller.AlipaySandboxController;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.payment.service.*;
import com.threadfit.glucopilot.wallet.controller.WalletOrderController;
import com.threadfit.glucopilot.wallet.mapper.WalletOrderMapper;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

class WalletOrderTest {
    private SqlSession session;
    private MockMvc web;
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        RequestContext.clear();
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:orders_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE wallet_sandbox_account(user_id BIGINT PRIMARY KEY,balance_cents"
                        + " BIGINT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT DEFAULT"
                        + " 0,account_status INT DEFAULT 1,wallet_activated_at TIMESTAMP(3))");
        jdbc.execute(
                "CREATE TABLE wallet_sandbox_ledger(order_no VARCHAR(64) UNIQUE,user_id BIGINT,"
                        + "balance_after_cents BIGINT)");
        jdbc.execute(
                "CREATE TABLE consultation_wallet_entry(order_no VARCHAR(64),user_id BIGINT,"
                        + "balance_after_cents BIGINT,UNIQUE(order_no,user_id))");
        jdbc.update("INSERT INTO user_account(id) VALUES(1),(2),(3)");
        // SQL投影测试夹具；MySQL完整迁移（生成列/唯一约束）另外执行实际验证。
        String common =
                "id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(64),user_id"
                        + " BIGINT,amount_cents INT,currency VARCHAR(3) DEFAULT 'CNY',status"
                        + " VARCHAR(16),gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,paid_at"
                        + " TIMESTAMP,closed_at TIMESTAMP";
        jdbc.execute("CREATE TABLE wallet_recharge_order(" + common + ",credited_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE consultation_order("
                        + common
                        + ",consultation_id BIGINT,report_id BIGINT,doctor_user_id"
                        + " BIGINT,settlement_status VARCHAR(16))");
        jdbc.update(
                "INSERT INTO wallet_recharge_order(order_no,user_id,amount_cents,status) VALUES"
                    + "('r1',1,1000,'PENDING'),('r2',1,500,'PAID'),('private-r',2,50000,'PAID')");
        jdbc.update(
                "INSERT INTO"
                    + " consultation_order(order_no,user_id,amount_cents,status,consultation_id,report_id,doctor_user_id,settlement_status)"
                    + " VALUES"
                    + "('c1',1,100,'PENDING',10,20,3,'NOT_READY'),('private-c',2,100,'PAID',11,21,3,'NOT_READY')");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(WalletOrderMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        var properties = new AlipaySandboxProperties();
        var wallets =
                new WalletOrderService(session.getMapper(WalletOrderMapper.class), properties);
        var controller = new WalletOrderController(wallets);
        var paymentMapper = org.mockito.Mockito.mock(SandboxPaymentMapper.class);
        var recharges = new SandboxRechargeService(paymentMapper, properties);
        var payments =
                new SandboxPaymentService(
                        recharges, paymentMapper, org.mockito.Mockito.mock(SandboxGateway.class));
        web =
                MockMvcBuilders.standaloneSetup(
                                controller,
                                new AlipaySandboxController(recharges, payments, wallets),
                                new ConsultationController(org.mockito.Mockito.mock(ConsultationService.class), wallets))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void cleanup() {
        RequestContext.clear();
        session.close();
    }

    private JsonNode getData(String path) throws Exception {
        var response = web.perform(get("/api/v1" + path)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        return json.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void activationIsOptInIdempotentAndIsolatedBetweenAccounts() throws Exception {
        RequestContext.setUserId(1L);
        assertTrue(getData("/wallet").path("available").asBoolean());
        assertFalse(getData("/wallet").path("activated").asBoolean());
        var first =
                web.perform(post("/api/v1/wallet/activation?userId=2")).andReturn().getResponse();
        assertEquals(200, first.getStatus());
        var activated = json.readTree(first.getContentAsString()).path("data");
        assertTrue(activated.path("activated").asBoolean());
        assertTrue(activated.path("paymentEnabled").asBoolean());
        String time = activated.path("activatedAt").asString();
        var second = web.perform(post("/api/v1/wallet/activation")).andReturn().getResponse();
        assertEquals(
                time,
                json.readTree(second.getContentAsString())
                        .path("data")
                        .path("activatedAt")
                        .asString());
        RequestContext.setUserId(2L);
        assertFalse(getData("/wallet").path("activated").asBoolean());
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recharge_order", Integer.class));
        jdbc.update("UPDATE user_account SET is_deleted=1 WHERE id=3");
        RequestContext.setUserId(3L);
        assertEquals(
                404,
                web.perform(post("/api/v1/wallet/activation"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        RequestContext.clear();
        assertEquals(
                401,
                web.perform(post("/api/v1/wallet/activation"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }

    @Test
    void requiresPatientIdentityAndDoesNotInventBalanceFromPaidOrders() throws Exception {
        RequestContext.setOpsId(1L);
        for (String route :
                new String[] {
                    "/wallet",
                    "/wallet/recharge-orders",
                    "/consultation-orders",
                    "/wallet/recharge-orders/r1"
                }) {
            assertEquals(
                    401,
                    web.perform(get("/api/v1" + route + "?userId=1"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
        RequestContext.setUserId(1L);
        var summary = getData("/wallet");
        assertTrue(summary.path("balanceCents").isNull());
        assertTrue(summary.path("ledgerReady").asBoolean());
        assertTrue(summary.path("sandbox").asBoolean());
        assertFalse(summary.path("rechargeEnabled").asBoolean());
        assertFalse(summary.path("paymentEnabled").asBoolean());
        assertEquals(100, summary.path("consultationFeeCents").asInt());
    }

    @Test
    void actualXmlFiltersOwnersStatusesAndPaginatesBothOrderTypes() throws Exception {
        RequestContext.setUserId(1L);
        var all = getData("/wallet/recharge-orders?pageSize=1&pageNum=1&userId=2");
        assertEquals(2, all.path("total").asInt());
        assertEquals(2, all.path("pages").asInt());
        assertEquals(1, all.path("records").size());
        assertEquals("r2", all.path("records").get(0).path("orderNo").asString());
        var second = getData("/wallet/recharge-orders?pageSize=1&pageNum=2");
        assertEquals("r1", second.path("records").get(0).path("orderNo").asString());
        var paid = getData("/wallet/recharge-orders?status=PAID");
        assertEquals(1, paid.path("total").asInt());
        assertEquals("RECHARGE", paid.path("records").get(0).path("type").asString());
        var consultation = getData("/consultation-orders");
        assertEquals(1, consultation.path("total").asInt());
        var item = consultation.path("records").get(0);
        assertEquals("CONSULTATION", item.path("type").asString());
        assertEquals(100, item.path("amountCents").asInt());
        assertEquals(20, item.path("reportId").asInt());
        assertEquals(50, getData("/consultation-orders?pageSize=100").path("pageSize").asInt());
    }

    @Test
    void rechargeBalanceUsesHistoricalLedgerAndMissingOrUnpaidRecordsStayNull() throws Exception {
        RequestContext.setUserId(1L);
        jdbc.update("INSERT INTO wallet_sandbox_account VALUES(1,77777)");
        jdbc.update("INSERT INTO wallet_sandbox_ledger VALUES('r2',1,1500),('r1',1,9999)");
        assertEquals(1500, getData("/wallet/recharge-orders/r2").path("balanceAfterCents").asLong());
        var list = getData("/wallet/recharge-orders?status=PAID").path("records");
        assertEquals(1500, list.get(0).path("balanceAfterCents").asLong());
        assertTrue(getData("/wallet/recharge-orders/r1").path("balanceAfterCents").isNull());
        jdbc.update("UPDATE wallet_sandbox_account SET balance_cents=100 WHERE user_id=1");
        session.clearCache();
        assertEquals(1500, getData("/wallet/recharge-orders/r2").path("balanceAfterCents").asLong());
        jdbc.update("DELETE FROM wallet_sandbox_ledger WHERE order_no='r2'");
        session.clearCache();
        assertTrue(getData("/wallet/recharge-orders/r2").path("balanceAfterCents").isNull());
        jdbc.update("INSERT INTO wallet_sandbox_ledger VALUES('r2',2,6000)");
        session.clearCache();
        assertTrue(getData("/wallet/recharge-orders/r2").path("balanceAfterCents").isNull());
    }

    @Test
    void consultationBalanceBelongsToPatientAndZeroIsNotMissing() throws Exception {
        RequestContext.setUserId(1L);
        jdbc.update("UPDATE consultation_order SET status='PAID' WHERE order_no='c1'");
        jdbc.update("INSERT INTO consultation_wallet_entry VALUES('c1',1,0),('c1',3,9900)");
        var detail = getData("/consultation-orders/c1");
        assertFalse(detail.path("balanceAfterCents").isNull());
        assertEquals(0, detail.path("balanceAfterCents").asLong());
        assertEquals(
                0,
                getData("/consultation-orders").path("records").get(0)
                        .path("balanceAfterCents").asLong());
        jdbc.update("DELETE FROM consultation_wallet_entry WHERE user_id=1");
        session.clearCache();
        assertTrue(getData("/consultation-orders/c1").path("balanceAfterCents").isNull());
    }

    @Test
    void detailsNeverExposeOtherPatientsOrdersOrReportContent() throws Exception {
        RequestContext.setUserId(1L);
        assertEquals("r1", getData("/wallet/recharge-orders/r1").path("orderNo").asString());
        assertEquals("c1", getData("/consultation-orders/c1").path("orderNo").asString());
        for (String path :
                new String[] {
                    "/wallet/recharge-orders/private-r",
                    "/consultation-orders/private-c",
                    "/consultation-orders/missing"
                }) {
            assertEquals(
                    404, web.perform(get("/api/v1" + path)).andReturn().getResponse().getStatus());
        }
        var detail = getData("/consultation-orders/c1");
        assertFalse(detail.has("assessment"));
        assertFalse(detail.has("providerTradeNo"));
    }

    @Test
    void reservedRechargeValidatesMoneyWithoutCreatingOrders() throws Exception {
        RequestContext.setUserId(1L);
        for (int cents : new int[] {0, 99, 100001}) {
            assertEquals(
                    400,
                    web.perform(
                                    post("/api/v1/wallet/recharge-orders")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"amountCents\":"
                                                            + cents
                                                            + ",\"clientRequestId\":\"recharge-001\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
        var response =
                web.perform(
                                post("/api/v1/wallet/recharge-orders")
                                        .contentType("application/json")
                                        .content(
                                                "{\"amountCents\":1000,\"clientRequestId\":\"recharge-001\"}"))
                        .andReturn()
                        .getResponse();
        assertEquals(501, response.getStatus());
        assertEquals(50100, json.readTree(response.getContentAsString()).path("code").asInt());
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recharge_order", Integer.class));
        for (String query : new String[] {"?status=HACK", "?pageNum=0", "?pageSize=101"}) {
            assertEquals(
                    400,
                    web.perform(get("/api/v1/consultation-orders" + query))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
    }
}
