package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alipay.api.*;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;
import com.threadfit.glucopilot.payment.service.AlipaySandboxGateway;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

class SandboxGatewayDiagnosticsTest {
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
