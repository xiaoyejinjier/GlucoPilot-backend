package com.threadfit.glucopilot.auth.client;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ToString(exclude="appSecret")
@ConfigurationProperties(prefix="wechat.login")
public class WechatLoginProperties {
    public enum Mode { AUTO, WECHAT, MOCK }
    private Mode mode=Mode.AUTO;
    private String appId="";
    private String appSecret="";
}
