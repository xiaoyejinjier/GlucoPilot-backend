package com.threadfit.glucopilot.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.auth.client.WechatAuthClient;
import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.entity.AuthEmailCode;
import com.threadfit.glucopilot.auth.entity.AuthSmsCode;
import com.threadfit.glucopilot.auth.mail.EmailCodeMailSender;
import com.threadfit.glucopilot.auth.mapper.*;
import com.threadfit.glucopilot.auth.security.UserSessionAuthFilter;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.AuditMetaObjectHandler;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实MyBatis与独立事务验证两个终端/服务实例的会话替换，不连接共享基础设施。 */
class SingleUserSessionSqlTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private UserSessionMapper sessions;
    private java.sql.Connection schemaConnection;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:single_session_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO user_account(id) VALUES(1),(2)");
        // H2的IN型CHECK保留DDL会话比较器；跨事务测试需保持此会话存活。
        schemaConnection = ds.getConnection();
        try (var statement = schemaConnection.createStatement()) {
            statement.execute(
                    """
CREATE TABLE user_session(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,
  access_token VARCHAR(512) NOT NULL,login_type INT NOT NULL,
  expire_at TIMESTAMP(3) NOT NULL,client_ip VARCHAR(45),user_agent VARCHAR(512),
  is_valid INT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL DEFAULT 0,gmt_create TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP,
  updated_by BIGINT NOT NULL DEFAULT 0,gmt_modified TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP,
  deleted_by BIGINT,deleted_at TIMESTAMP(3),is_deleted INT NOT NULL DEFAULT 0,
  UNIQUE(access_token,is_deleted),CHECK(is_valid IN(0,1)),CHECK(login_type IN(1,2,3))
)
""");
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        var global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new AuditMetaObjectHandler());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(
                configuration, global);
        configuration.addMapper(UserSessionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        sessions = new SqlSessionTemplate(factory.getObject()).getMapper(UserSessionMapper.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        RequestContext.clear();
        SecurityContextHolder.clearContext();
        try {
            jdbc.execute("DROP ALL OBJECTS");
        } finally {
            if (schemaConnection != null) {
                schemaConnection.close();
            }
        }
    }

    private AuthService service(long userId, UserSettingMapper settings) {
        return service(
                userId, settings, mock(AuthSmsCodeMapper.class), mock(AuthEmailCodeMapper.class));
    }

    private AuthService service(
            long userId,
            UserSettingMapper settings,
            AuthSmsCodeMapper smsCodes,
            AuthEmailCodeMapper emailCodes) {
        var account = new UserAccount();
        account.setId(userId);
        account.setAccountStatus(1);
        var users = mock(UserAccountMapper.class);
        when(users.selectOne(any())).thenReturn(account);
        when(users.selectById(userId)).thenReturn(account);
        WechatAuthClient wechat =
                code -> new WechatAuthClient.WechatIdentity("test-openid-" + userId, null);
        return new AuthService(
                wechat,
                users,
                mock(UserProfileMapper.class),
                mock(UserHealthMapper.class),
                settings,
                sessions,
                smsCodes,
                emailCodes,
                mock(EmailCodeMailSender.class));
    }

    private AuthService service(long userId) {
        return service(userId, mock(UserSettingMapper.class));
    }

    private String login(AuthService auth) {
        var request = new AuthDtos.WechatLoginRequest();
        request.setCode(UUID.randomUUID().toString());
        return tx.execute(ignored -> auth.loginByWechat(request).getAccessToken());
    }

    private long activeCount(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE user_id=? AND is_valid=1",
                Long.class,
                userId);
    }

    @Test
    void wechatPhoneAndEmailReplaceEachOtherForTheSameAccount() {
        var smsCodes = mock(AuthSmsCodeMapper.class);
        var sms = new AuthSmsCode();
        sms.setVerifyCode("123456");
        sms.setExpireAt(LocalDateTime.now().plusMinutes(5));
        when(smsCodes.selectOne(any())).thenReturn(sms);
        var emailCodes = mock(AuthEmailCodeMapper.class);
        var email = new AuthEmailCode();
        email.setVerifyCode("654321");
        email.setExpireAt(LocalDateTime.now().plusMinutes(5));
        when(emailCodes.selectOne(any())).thenReturn(email);
        var auth = service(1, mock(UserSettingMapper.class), smsCodes, emailCodes);
        String wechatToken = login(auth);
        var phoneRequest = new AuthDtos.PhoneLoginRequest();
        phoneRequest.setPhone("13800000000");
        phoneRequest.setSmsCode("123456");
        String phoneToken = tx.execute(ignored -> auth.loginByPhone(phoneRequest).getAccessToken());
        assertEquals(
                ErrorCode.SESSION_REPLACED,
                assertThrows(BizException.class, () -> auth.resolveSession(wechatToken))
                        .getErrorCode());
        assertEquals(2, auth.requireValidSession(phoneToken).getLoginType());
        var emailRequest = new AuthDtos.EmailLoginRequest();
        emailRequest.setEmail("test@example.test");
        emailRequest.setEmailCode("654321");
        String emailToken = tx.execute(ignored -> auth.loginByEmail(emailRequest).getAccessToken());
        assertEquals(
                ErrorCode.SESSION_REPLACED,
                assertThrows(BizException.class, () -> auth.resolveSession(phoneToken))
                        .getErrorCode());
        assertEquals(3, auth.requireValidSession(emailToken).getLoginType());
        assertEquals(1, activeCount(1));
    }

    @Test
    void latestLoginReplacesOldTokensAcrossServiceInstancesAndLogoutCannotRevokeTheNewOne() {
        var first = service(1);
        var second = service(1);
        String oldToken = login(first);
        String newToken = login(second);
        assertNotEquals(oldToken, newToken);
        assertEquals(1, activeCount(1));
        assertEquals(
                ErrorCode.SESSION_REPLACED,
                assertThrows(BizException.class, () -> first.resolveSession(oldToken))
                        .getErrorCode());
        assertEquals(
                ErrorCode.SESSION_REPLACED,
                assertThrows(BizException.class, () -> first.requireValidSession(oldToken))
                        .getErrorCode());
        assertEquals(1L, second.resolveSession(newToken).userId());
        tx.executeWithoutResult(ignored -> first.logout(oldToken));
        assertNotNull(second.resolveSession(newToken));
        tx.executeWithoutResult(ignored -> second.logout(newToken));
        assertNull(second.resolveSession(newToken));
        assertEquals(0, activeCount(1));
    }

    @Test
    void differentAccountsRemainLoggedInAndExpiredTokensUseNormalExpiry() {
        var first = service(1);
        var other = service(2);
        String oldToken = login(first);
        String otherToken = login(other);
        assertNotNull(first.resolveSession(oldToken));
        assertNotNull(other.resolveSession(otherToken));
        login(first);
        jdbc.update(
                "UPDATE user_session SET expire_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE"
                        + " access_token=?",
                oldToken);
        assertNull(first.resolveSession(oldToken));
        assertNotNull(other.resolveSession(otherToken));
    }

    @Test
    void failedLoginRollsBackRevocationAndPreservesThePreviousSession() {
        var first = service(1);
        String oldToken = login(first);
        var brokenSettings = mock(UserSettingMapper.class);
        when(brokenSettings.selectOne(any()))
                .thenThrow(new IllegalStateException("response failure"));
        assertThrows(IllegalStateException.class, () -> login(service(1, brokenSettings)));
        assertEquals(1, activeCount(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_session", Long.class));
        assertNotNull(first.resolveSession(oldToken));
    }

    @Test
    void concurrentLoginsStillLeaveExactlyOneValidSession() throws Exception {
        var first = service(1);
        var second = service(1);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return login(first);
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return login(second);
                            });
            start.countDown();
            String tokenA = a.get(10, TimeUnit.SECONDS);
            String tokenB = b.get(10, TimeUnit.SECONDS);
            assertEquals(1, activeCount(1));
            String valid =
                    jdbc.queryForObject(
                            "SELECT access_token FROM user_session WHERE is_valid=1", String.class);
            String replaced = valid.equals(tokenA) ? tokenB : tokenA;
            assertNotNull(first.resolveSession(valid));
            assertEquals(
                    ErrorCode.SESSION_REPLACED,
                    assertThrows(BizException.class, () -> second.resolveSession(replaced))
                            .getErrorCode());
        }
    }

    @Test
    void replacedTokenReturnsExplicit401WithoutReachingTheController() throws Exception {
        var auth = service(1);
        String oldToken = login(auth);
        String newToken = login(auth);
        var filter = new UserSessionAuthFilter(auth, new JsonUtils(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer " + oldToken);
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        RequestContext.setRequestId("single-session-test");
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(401, response.getStatus());
        assertEquals(40101, body.path("code").asInt());
        assertEquals("single-session-test", body.path("requestId").asString());
        assertFalse(reached.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        var validRequest = new MockHttpServletRequest("GET", "/api/v1/users/me");
        validRequest.addHeader("Authorization", "Bearer " + newToken);
        filter.doFilter(
                validRequest,
                new MockHttpServletResponse(),
                (req, res) -> {
                    reached.set(true);
                    assertEquals(1L, RequestContext.getUserId());
                });
        assertTrue(reached.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
