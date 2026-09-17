package com.threadfit.glucopilot.auth.client;

/**
 * 微信登录凭证兑换。生产接微信 API；开发用 Mock。
 */
public interface WechatAuthClient {

    record WechatIdentity(String openid, String unionId) {
    }

    WechatIdentity exchangeCode(String code);
}
