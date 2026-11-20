package com.threadfit.glucopilot.device.cloud;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "device.cloud")
public class DeviceCloudProperties {

    /** Base64 AES key；与 TokenCrypto 共用。 */
    private String tokenAesKey = "";

    /** 同一用户+设备云拉取最短间隔（秒）。 */
    private int pullMinIntervalSeconds = 60;

    private HuaweiCloudProperties huawei = new HuaweiCloudProperties();
}
