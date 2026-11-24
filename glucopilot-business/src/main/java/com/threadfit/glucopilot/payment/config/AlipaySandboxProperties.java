package com.threadfit.glucopilot.payment.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

/** 绑定payments.alipay.sandbox配置并检查格式；密钥通过外部配置注入，源码不内置密钥。 */
@Component
@ConfigurationProperties(prefix = "payments.alipay.sandbox")
@Getter
@Setter
public class AlipaySandboxProperties {
    // 网关固定为沙箱；此模块没有切换真实支付宝网关的配置。
    public static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    // 总开关：即使填写了密钥，也要明确启用后才允许生成付款信息或转账。
    private boolean enabled;
    // 沙箱应用ID，用于标识哪个应用在请求支付宝。
    private String appId = "";
    // 应用私钥：在后端给请求签名，由本机/部署环境注入，不应交给小程序。
    private String privateKey = "";
    // 支付宝公钥：验证支付宝响应及回调；不能填写成“应用公钥”。
    private String publicKey = "";
    // 沙箱商家PID：充值的收款商家，也用于核对订单归属。
    private String sellerId = "";
    // 可选公网回调地址；留空时通过后端主动查单确认付款。
    private String notifyUrl = "";
    // 提现转账的业务场景及报备信息类型，提交给支付宝做对应场景校验。
    private String transferSceneName = "业务结算";
    private String transferSceneInfoType = "结算款项名称";

    // 提现除了基本支付配置，还要求场景及信息类型非空；产品是否可用仍由支付宝接口确认。
    public boolean transferReady() {
        return ready() && !transferSceneName.isBlank() && !transferSceneInfoType.isBlank();
    }

    // 仅判断开关与配置格式是否齐备；密钥解析和响应验签在网关中完成。
    public boolean ready() {
        return enabled
                && appId.matches("[0-9]{10,32}")
                && sellerId.matches("[0-9]{10,32}")
                && !privateKey.isBlank()
                && !publicKey.isBlank()
                && validNotifyUrl();
    }

    // 当前实现只允许HTTPS及固定回调路径，拒绝夹带账号、查询参数或片段；空值允许本地查单测试。
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
