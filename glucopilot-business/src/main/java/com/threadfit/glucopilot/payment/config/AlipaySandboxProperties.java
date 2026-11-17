package com.threadfit.glucopilot.payment.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@ConfigurationProperties(prefix = "payments.alipay.sandbox")
@Getter
@Setter
public class AlipaySandboxProperties {
    // 网关固定为沙箱；此模块没有切换真实支付宝网关的配置。
    public static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private boolean enabled;
    private String appId = "";
    private String privateKey = "";
    private String publicKey = "";
    private String sellerId = "";
    private String notifyUrl = "";

    public boolean ready() {
        return enabled
                && appId.matches("[0-9]{10,32}")
                && sellerId.matches("[0-9]{10,32}")
                && !privateKey.isBlank()
                && !publicKey.isBlank()
                && validNotifyUrl();
    }

    private boolean validNotifyUrl() {
        if (notifyUrl.isBlank()) return true;
        try {
            URI uri = URI.create(notifyUrl);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && "/api/v1/payments/alipay/sandbox/notify".equals(uri.getPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
