package com.threadfit.glucopilot.auth.client;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.IdUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 开发用 Mock：code 非空即换成本地 openid（wx_ + code 哈希后缀）。
 */
@Component
public class MockWechatAuthClient implements WechatAuthClient {

    @Override
    public WechatIdentity exchangeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        String openid = "wx_" + IdUtils.uuid().substring(0, 16);
        // 同一 code 多次兑换在真实微信会失效；Mock 每次新 openid 便于联调「新用户」
        // 若要稳定用户，可用 code 本身派生：
        openid = "wx_" + Integer.toHexString(code.trim().hashCode());
        return new WechatIdentity(openid, null);
    }
}
