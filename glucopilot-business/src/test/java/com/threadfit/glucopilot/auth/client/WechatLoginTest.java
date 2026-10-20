package com.threadfit.glucopilot.auth.client;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WechatLoginTest {
    private WechatLoginProperties properties() {
        var p=new WechatLoginProperties();p.setAppId("wx-test-app");p.setAppSecret("test-server-only-secret");return p;
    }
    private OfficialWechatAuthClient client(WechatLoginProperties p,RestClient.Builder builder) { return new OfficialWechatAuthClient(p,builder); }
    @Test void differentWechatUsersAreDifferentAndSameWechatWithNewCodeIsStable() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        String[] codes={"code-A-first","code-B-first","code-A-again"};String[] openids={"wechat-A","wechat-B","wechat-A"};
        for(int i=0;i<codes.length;i++) {
            final String code=codes[i];
            server.expect(request->{URI uri=request.getURI();assertEquals("https",uri.getScheme());assertEquals("api.weixin.qq.com",uri.getHost());assertEquals("/sns/jscode2session",uri.getPath());assertTrue(uri.getQuery().contains("js_code="+code));assertTrue(uri.getQuery().contains("grant_type=authorization_code"));})
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"openid\":\""+openids[i]+"\",\"session_key\":\"server-only-session-key\"}",MediaType.TEXT_PLAIN));
        }
        var client=client(properties(),builder);var a=client.exchangeCode(codes[0]);var b=client.exchangeCode(codes[1]);var again=client.exchangeCode(codes[2]);
        assertNotEquals(a.openid(),b.openid());assertEquals(a.openid(),again.openid());assertNull(a.unionId());server.verify();
    }
    @Test void badCodeUpstreamFailureAndMalformedPayloadNeverLeakSecretsOrBecomeMock() {
        for(String payload:List.of("{\"errcode\":40029,\"errmsg\":\"test-server-only-secret\"}","not-json", "{\"openid\":12,\"session_key\":\"key\"}","{\"openid\":\"valid\"}")) {
            var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
            var error=assertThrows(BizException.class,()->client(properties(),builder).exchangeCode("one-use-code"));
            assertFalse(error.getMessage().contains("test-server-only-secret"));assertFalse(error.getMessage().contains("one-use-code"));assertNull(error.getCause());server.verify();
        }
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withServerError().body("test-server-only-secret"));
        assertEquals(ErrorCode.INTERNAL_ERROR,assertThrows(BizException.class,()->client(properties(),builder).exchangeCode("code")).getErrorCode());server.verify();
    }
    @Test void missingOrPartialConfigurationDoesNotMakeNetworkCallsOrMockIdentity() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        assertEquals(ErrorCode.BIZ_REJECTED,assertThrows(BizException.class,()->client(new WechatLoginProperties(),builder).exchangeCode("code")).getErrorCode());server.verify();
        var config=new WechatLoginConfiguration();var env=new MockEnvironment();env.setActiveProfiles("dev","local");
        var partial=new WechatLoginProperties();partial.setAppId("app-only");
        var selected=config.wechatAuthClient(partial,env,"shared-mock-id");
        assertInstanceOf(OfficialWechatAuthClient.class,selected);
        assertThrows(BizException.class,()->selected.exchangeCode("valid-code"));
    }
    @Test void mockIsExplicitlyRestrictedToDevelopmentEvenWhenProdAndDevAreBothActive() {
        var config=new WechatLoginConfiguration();var env=new MockEnvironment();env.setActiveProfiles("dev");
        assertInstanceOf(MockWechatAuthClient.class,config.wechatAuthClient(new WechatLoginProperties(),env,"mock-user"));
        var p=new WechatLoginProperties();p.setMode(WechatLoginProperties.Mode.MOCK);env.setActiveProfiles("dev","prod");
        assertThrows(BizException.class,()->config.wechatAuthClient(p,env,"mock-user").exchangeCode("any-code"));
        p.setMode(WechatLoginProperties.Mode.WECHAT);env.setActiveProfiles("dev");
        assertInstanceOf(OfficialWechatAuthClient.class,config.wechatAuthClient(p,env,"mock-user"));
    }
    @Test void credentialsSelectRealWechatEvenWithAnOldFixedMockUserConfigured() {
        var env=new MockEnvironment();env.setActiveProfiles("dev","local");
        assertInstanceOf(OfficialWechatAuthClient.class,new WechatLoginConfiguration().wechatAuthClient(properties(),env,"old-shared-user"));
    }
}
