package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.service.AlipaySandboxGateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.stream.Collectors;

class SandboxGatewaySignatureTest {
    @Test
    void verifiesRsa2AndRejectsTamperingOrDisabledConfiguration() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var config = new AlipaySandboxProperties();
        config.setEnabled(true);
        config.setAppId("2026000000000001");
        config.setSellerId("2088000000000001");
        config.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        config.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var params = new TreeMap<String, String>();
        params.put("app_id", config.getAppId());
        params.put("seller_id", config.getSellerId());
        params.put("out_trade_no", "SR_test_001");
        params.put("trade_no", "test_trade");
        params.put("total_amount", "10.00");
        params.put("trade_status", "TRADE_SUCCESS");
        String content =
                params.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("&"));
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(content.getBytes(StandardCharsets.UTF_8));
        params.put("sign_type", "RSA2");
        params.put("sign", Base64.getEncoder().encodeToString(signer.sign()));
        var gateway = new AlipaySandboxGateway(config);
        assertTrue(gateway.verify(params));
        params.put("total_amount", "100.00");
        assertFalse(gateway.verify(params));
        params.put("total_amount", "10.00");
        config.setEnabled(false);
        assertFalse(gateway.verify(params));
        assertEquals(
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                AlipaySandboxProperties.GATEWAY);
    }
}
