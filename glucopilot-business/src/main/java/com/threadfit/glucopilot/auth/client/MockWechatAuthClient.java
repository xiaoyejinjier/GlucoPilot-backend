package com.threadfit.glucopilot.auth.client;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/**
 * 开发用 Mock：code 非空即换成本地 openid。
 *
 * <h2>默认行为（按 code 派生）与它带来的坑</h2>
 * 默认把 {@code code} 的哈希当作 openid 后缀，于是同一个 code 换到同一个用户、不同 code 换到不同用户。
 * 问题是微信开发者工具里 {@code wx.login()} <b>每次都会给出新的 code</b>，所以每次重新登录
 * （含清缓存后重进）都会创建一个<b>全新用户</b>，表现为：
 * 「每次都要重新同意免责声明」「聊天会话与血糖记录攒不在同一个人身上」；
 * 而 {@code app.js} 的 {@code onShow} 免责校验会在未同意时把页面强制拉回声明页，
 * 看起来就像「AI 对话页打不开」。这不是前端故障，是开发身份不稳定。
 *
 * <h2>{@code wechat.mock.fixed-openid}</h2>
 * 配了它就<b>恒定返回同一个 openid</b>（不再看 code），开发时登录一次、之后一直是同一个用户。
 * 刻意做成显式配置而不是默认值：
 * <ul>
 *   <li>要联调「新用户」流程（首登、免责声明、空数据）时留空即可退回老行为；</li>
 *   <li>它是只在开发环境该生效的开关：写在 {@code application-dev.yaml}（留空说明用途）
 *       或 {@code application-local.yaml}（填值，该文件已 gitignore），不要写进生产 profile。</li>
 * </ul>
 */
@Slf4j
public class MockWechatAuthClient implements WechatAuthClient {

    /** 开发用固定 openid；留空 = 按 code 哈希派生（每个 code 一个用户）。 */
    private final String fixedOpenid;

    public MockWechatAuthClient(@Value("${wechat.mock.fixed-openid:}") String fixedOpenid) {
        this.fixedOpenid = fixedOpenid == null ? "" : fixedOpenid.trim();
        if (!this.fixedOpenid.isEmpty()) {
            log.warn("开发用固定微信身份已启用：所有微信登录都会映射到同一个开发用户；双账号真机请配置真实微信登录");
        }
    }

    @Override
    public WechatIdentity exchangeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        if (!fixedOpenid.isEmpty()) {
            return new WechatIdentity(fixedOpenid, null);
        }
        // 同一 code 多次兑换在真实微信会失效；Mock 按 code 派生，便于用固定 code 复现同一个用户
        String openid = "wx_" + Integer.toHexString(code.trim().hashCode());
        return new WechatIdentity(openid, null);
    }
}
