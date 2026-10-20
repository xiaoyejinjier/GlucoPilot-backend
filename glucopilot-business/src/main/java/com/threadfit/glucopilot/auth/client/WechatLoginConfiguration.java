package com.threadfit.glucopilot.auth.client;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;

@Configuration
@EnableConfigurationProperties(WechatLoginProperties.class)
public class WechatLoginConfiguration {
    @Bean
    public WechatAuthClient wechatAuthClient(WechatLoginProperties p,Environment environment,
            @Value("${wechat.mock.fixed-openid:}") String fixedOpenid) {
        var profiles=Arrays.asList(environment.getActiveProfiles().length>0?environment.getActiveProfiles():environment.getDefaultProfiles());
        boolean development=!profiles.contains("prod") && (profiles.contains("dev") || profiles.contains("test"));
        boolean configured=p.getAppId()!=null && !p.getAppId().isBlank() && p.getAppSecret()!=null && !p.getAppSecret().isBlank();
        boolean partial=(p.getAppId()!=null && !p.getAppId().isBlank()) != (p.getAppSecret()!=null && !p.getAppSecret().isBlank());
        if(p.getMode()==WechatLoginProperties.Mode.MOCK || (p.getMode()==WechatLoginProperties.Mode.AUTO && !configured && !partial && development)) {
            if(!development) return code->{throw new BizException(ErrorCode.BIZ_REJECTED,"当前环境不允许模拟微信登录");};
            return new MockWechatAuthClient(fixedOpenid);
        }
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        return new OfficialWechatAuthClient(p,RestClient.builder().requestFactory(factory));
    }
}
