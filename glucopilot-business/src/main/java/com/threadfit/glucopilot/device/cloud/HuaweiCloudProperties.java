package com.threadfit.glucopilot.device.cloud;

import lombok.Data;

@Data
public class HuaweiCloudProperties {

    /** 总开关；未配置 client 时业务层仍拒绝。 */
    private boolean enabled = false;

    private String clientId = "";
    private String clientSecret = "";
    /** 公网 HTTPS 回调，须与华为控制台一致。 */
    private String redirectUri = "";
    private String authorizeBase = "https://oauth-login.cloud.huawei.com/oauth2/v3/authorize";
    private String tokenUrl = "https://oauth-login.cloud.huawei.com/oauth2/v3/token";
    private String apiBase = "https://health-api.cloud.huawei.com/healthkit/v2";
    /** 血糖相关 scope，空格分隔。 */
    private String scopes = "openid https://www.huawei.com/healthkit/bloodglucose.read";
    /** 授权成功/失败后回跳小程序或中间页。 */
    private String returnUrl = "";
}
