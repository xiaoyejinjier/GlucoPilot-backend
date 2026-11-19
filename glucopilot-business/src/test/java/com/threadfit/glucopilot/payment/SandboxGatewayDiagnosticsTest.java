package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipayLogger;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;
import com.threadfit.glucopilot.payment.service.AlipaySandboxGateway;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

class SandboxGatewayDiagnosticsTest {
    @Test
    void pagePaymentUsesSdkSignedSandboxUrlAndServerOrderFields() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var applicationKey = generator.generateKeyPair();
        var alipayKey = generator.generateKeyPair();
        var properties = configuration();
        properties.setPrivateKey(
                Base64.getEncoder().encodeToString(applicationKey.getPrivate().getEncoded()));
        properties.setPublicKey(
                Base64.getEncoder().encodeToString(alipayKey.getPublic().getEncoded()));
        var order = order();
        order.setExpiresAt(java.time.LocalDateTime.of(2026, 11, 18, 17, 30));
        String url = new AlipaySandboxGateway(properties).pagePay(order);
        var uri = java.net.URI.create(url);
        assertEquals("https", uri.getScheme());
        assertEquals("openapi-sandbox.dl.alipaydev.com", uri.getHost());
        var parameters = new java.util.HashMap<String, String>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            parameters.put(
                    pair[0],
                    java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("alipay.trade.page.pay", parameters.get("method"));
        assertEquals(properties.getAppId(), parameters.get("app_id"));
        String business = parameters.get("biz_content");
        assertTrue(business.contains("\"out_trade_no\":\"SR_test\""));
        assertTrue(business.contains("\"total_amount\":\"1.00\""));
        assertTrue(business.contains("FAST_INSTANT_TRADE_PAY"));
        assertTrue(business.contains("2026-11-18 17:30:00"));
        // 请求签名包含sign_type；回调rsaCheckV1采用另一套参数排除规则。
        String signature = parameters.remove("sign");
        String signedContent =
                new java.util.TreeMap<>(parameters)
                        .entrySet().stream()
                                .filter(entry -> !entry.getValue().isEmpty())
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .collect(java.util.stream.Collectors.joining("&"));
        var verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(applicationKey.getPublic());
        verifier.update(signedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)));
        assertFalse(url.contains(properties.getPrivateKey()));
    }

    @Test
    void queryLogsActualStatusWithoutRawSdkPayloadAndTreatsMissingTradeAsPending()
            throws Exception {
        var client = mock(AlipayClient.class);
        var response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo("SR_test");
        response.setTradeNo("trade_fixture");
        response.setTradeStatus("WAIT_BUYER_PAY");
        response.setTotalAmount("1.00");
        response.setBody("sensitive_body_fixture");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        var gateway =
                new AlipaySandboxGateway(configuration()) {
                    @Override
                    protected AlipayClient client() {
                        return client;
                    }
                };
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(AlipaySandboxGateway.class);
        var sdkLogger = (Logger) LoggerFactory.getLogger("sdk.biz.err");
        logger.addAppender(appender);
        sdkLogger.addAppender(appender);
        try {
            var proof = gateway.query(order());
            assertEquals("WAIT_BUYER_PAY", proof.status());
            assertEquals("SR_test", proof.orderNo());
            String logged = appender.list.toString();
            assertTrue(logged.contains("tradeStatus=WAIT_BUYER_PAY"));
            assertTrue(logged.contains("code=10000"));
            assertFalse(logged.contains("sensitive_body_fixture"));
            assertFalse(logged.contains("trade_fixture"));

            // SDK 原始报文日志已关闭，不能绕过白名单输出签名或密钥。
            AlipayLogger.logBizError("sensitive_sdk_payload_fixture");
            assertFalse(appender.list.toString().contains("sensitive_sdk_payload_fixture"));

            response.setCode("40004");
            response.setSubCode("ACQ.TRADE_NOT_EXIST");
            response.setTradeStatus(null);
            assertNull(gateway.query(order()));
            assertTrue(appender.list.toString().contains("subCode=ACQ.TRADE_NOT_EXIST"));

            response.setCode("10000");
            response.setSubCode(null);
            response.setTradeStatus("sensitive_status_fixture\nforged_log");
            gateway.query(order());
            assertTrue(appender.list.toString().contains("tradeStatus=UNKNOWN"));
            assertFalse(appender.list.toString().contains("sensitive_status_fixture"));
        } finally {
            logger.detachAppender(appender);
            sdkLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private SandboxRecharge order() {
        var order = new SandboxRecharge();
        order.setOrderNo("SR_test");
        order.setAmountCents(100);
        return order;
    }

    private AlipaySandboxProperties configuration() {
        var properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId("2026000000000001");
        properties.setSellerId("2088000000000001");
        properties.setPrivateKey("private_key_fixture");
        properties.setPublicKey("public_key_fixture");
        return properties;
    }

    @Test
    void providerRejectionExposesOnlySafeCodeRatherThanResponseText() throws Exception {
        var client = mock(AlipayClient.class);
        var response = new AlipayTradePrecreateResponse();
        response.setCode("40004");
        response.setSubCode("ACQ.SELLER_NOT_EXIST");
        response.setSubMsg("sensitive_response_fixture");
        when(client.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);
        var gateway =
                new AlipaySandboxGateway(configuration()) {
                    @Override
                    protected AlipayClient client() {
                        return client;
                    }
                };
        var failure = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertTrue(failure.getMessage().contains("ACQ.SELLER_NOT_EXIST"));
        assertFalse(failure.getMessage().contains("sensitive_response_fixture"));
        assertEquals("PRECREATE", ((Map<?, ?>) failure.getData()).get("stage"));
        when(client.execute(any(AlipayTradePrecreateRequest.class)))
                .thenThrow(
                        new AlipayApiException("SDK_TEST_FAILURE", "sensitive_exception_fixture"));
        var sdk = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertTrue(sdk.getMessage().contains("SDK_TEST_FAILURE"));
        assertFalse(sdk.getMessage().contains("sensitive_exception_fixture"));
    }

    @Test
    void transportFailuresAreClassifiedWithoutExposingExceptionMessages() throws Exception {
        var client = mock(AlipayClient.class);
        var gateway =
                new AlipaySandboxGateway(configuration()) {
                    @Override
                    protected AlipayClient client() {
                        return client;
                    }
                };
        when(client.execute(any(AlipayTradePrecreateRequest.class)))
                .thenThrow(
                        new AlipayApiException(
                                new java.net.SocketTimeoutException(
                                        "sensitive_transport_fixture")));
        var timeout = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertEquals("SDK_TIMEOUT", ((Map<?, ?>) timeout.getData()).get("providerSubCode"));
        assertFalse(timeout.getMessage().contains("sensitive_transport_fixture"));
        when(client.execute(any(AlipayTradePrecreateRequest.class)))
                .thenThrow(
                        new AlipayApiException(
                                new javax.net.ssl.SSLHandshakeException("sensitive_tls_fixture")));
        var tls = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertEquals("SDK_TLS_ERROR", ((Map<?, ?>) tls.getData()).get("providerSubCode"));
        assertFalse(tls.getMessage().contains("sensitive_tls_fixture"));
    }

    @Test
    void recognizesOfficialSdkEnumExceptionsWithoutErrCodeAndHidesTheirParameters()
            throws Exception {
        var client = mock(AlipayClient.class);
        var gateway =
                new AlipaySandboxGateway(configuration()) {
                    @Override
                    protected AlipayClient client() {
                        return client;
                    }
                };
        var enumError = new AlipayApiException(AlipayApiErrorEnum.CHECK_SIGN_ERROR);
        assertNull(enumError.getErrCode());
        when(client.execute(any(AlipayTradePrecreateRequest.class))).thenThrow(enumError);
        var signature = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertEquals(
                "SDK_RESPONSE_SIGNATURE", ((Map<?, ?>) signature.getData()).get("providerSubCode"));
        when(client.execute(any(AlipayTradePrecreateRequest.class)))
                .thenThrow(new AlipayApiException(AlipayApiErrorEnum.CHECK_SIGN_JSON_ERROR));
        var json = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertEquals("CHECK_SIGN_JSON_ERROR", ((Map<?, ?>) json.getData()).get("providerSubCode"));
        assertFalse(json.getMessage().contains("%s"));
        when(client.execute(any(AlipayTradePrecreateRequest.class)))
                .thenThrow(
                        new AlipayApiException(AlipayApiErrorEnum.CHECK_ALIPAY_PUBLIC_KEY_ERROR));
        var publicKey = assertThrows(BizException.class, () -> gateway.precreate(order()));
        assertEquals(
                "CHECK_ALIPAY_PUBLIC_KEY_ERROR",
                ((Map<?, ?>) publicKey.getData()).get("providerSubCode"));
        assertTrue(publicKey.getMessage().contains("ALIPAY_SANDBOX_PUBLIC_KEY"));
    }

    @Test
    void malformedPrivateKeyFailsBeforeAnyNetworkCallWithoutExposingKey() {
        var failure =
                assertThrows(
                        BizException.class,
                        () -> new AlipaySandboxGateway(configuration()).precreate(order()));
        assertEquals(40000, failure.getErrorCode().getCode());
        assertEquals("APPLICATION_PRIVATE_KEY", ((Map<?, ?>) failure.getData()).get("key"));
        assertFalse(failure.getMessage().contains("private_key_fixture"));
    }

    @Test
    void applicationPublicKeyFailsBeforeAnyNetworkCallWithoutExposingKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var properties = configuration();
        properties.setPrivateKey(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));

        var failure =
                assertThrows(
                        BizException.class,
                        () -> new AlipaySandboxGateway(properties).precreate(order()));

        assertEquals(40000, failure.getErrorCode().getCode());
        assertEquals("KEY_PAIR_CHECK", ((Map<?, ?>) failure.getData()).get("stage"));
        assertEquals("ALIPAY_PUBLIC_KEY", ((Map<?, ?>) failure.getData()).get("key"));
        assertTrue(failure.getMessage().contains("ALIPAY_SANDBOX_PUBLIC_KEY"));
        assertFalse(failure.getMessage().contains(properties.getPrivateKey()));
        assertFalse(failure.getMessage().contains(properties.getPublicKey()));
    }
}
