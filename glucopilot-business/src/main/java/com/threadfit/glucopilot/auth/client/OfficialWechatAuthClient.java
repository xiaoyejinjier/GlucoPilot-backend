package com.threadfit.glucopilot.auth.client;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Credentials are sent only to the fixed WeChat endpoint; never expose upstream exceptions. */
public final class OfficialWechatAuthClient implements WechatAuthClient {
    private final WechatLoginProperties properties;
    private final RestClient client;
    public OfficialWechatAuthClient(WechatLoginProperties properties,RestClient.Builder builder) {
        this.properties=properties;
        this.client=builder.baseUrl("https://api.weixin.qq.com").build();
    }
    @Override public WechatIdentity exchangeCode(String code) {
        if(code==null || code.isBlank() || code.length()>512) throw new BizException(ErrorCode.BAD_REQUEST,"微信code不合法");
        if(properties.getAppId()==null || properties.getAppId().isBlank() || properties.getAppSecret()==null || properties.getAppSecret().isBlank())
            throw new BizException(ErrorCode.BIZ_REJECTED,"微信登录尚未配置，请联系管理员或使用手机号登录");
        String body;
        try {
            body=client.get().uri(uri->uri.path("/sns/jscode2session")
                    .queryParam("appid",properties.getAppId()).queryParam("secret",properties.getAppSecret())
                    .queryParam("js_code",code).queryParam("grant_type","authorization_code").build())
                    .retrieve().body(String.class);
        } catch(Exception ex) { throw new BizException(ErrorCode.INTERNAL_ERROR,"微信登录服务暂不可用，请稍后重新点击登录"); }
        try {
            var json=new ObjectMapper().readTree(body==null?"":body);
            if(json==null || !json.isObject()) throw new IllegalArgumentException();
            int error=json.has("errcode")?json.path("errcode").asInt(-999):0;
            if(error==40029 || error==40163) throw new BizException(ErrorCode.BIZ_REJECTED,"微信登录凭证已失效，请重新点击登录");
            if(error==45011 || error==-1) throw new BizException(ErrorCode.TOO_MANY_REQUESTS,"微信登录繁忙，请稍后重新点击登录");
            if(error!=0) throw new BizException(ErrorCode.INTERNAL_ERROR,"微信登录校验失败，请联系管理员检查配置");
            String openid=json.path("openid").asText("");
            String union=json.path("unionid").asText("");
            if(!json.path("openid").isTextual() || openid.isBlank() || openid.length()>64 || union.length()>64 ||
                    (json.hasNonNull("unionid") && !json.path("unionid").isTextual()) ||
                    !json.path("session_key").isTextual() || json.path("session_key").asText().isBlank())
                throw new IllegalArgumentException();
            return new WechatIdentity(openid,union.isBlank()?null:union);
        } catch(BizException ex) { throw ex; }
        catch(Exception ex) { throw new BizException(ErrorCode.INTERNAL_ERROR,"微信登录响应异常，请稍后重新点击登录"); }
    }
}
