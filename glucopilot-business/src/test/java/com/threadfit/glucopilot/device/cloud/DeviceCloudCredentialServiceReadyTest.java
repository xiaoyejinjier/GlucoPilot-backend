package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.mapper.DeviceCloudCredentialMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class DeviceCloudCredentialServiceReadyTest {

    @Mock DeviceCloudCredentialMapper credentialMapper;
    @Mock UserDeviceMapper userDeviceMapper;
    @Mock HuaweiOAuthClient huaweiOAuthClient;
    @Mock StringRedisTemplate redisTemplate;

    @Test
    void notReadyWhenDisabled() {
        DeviceCloudProperties props = new DeviceCloudProperties();
        props.getHuawei().setEnabled(false);
        TokenCrypto crypto = new TokenCrypto(Base64.getEncoder().encodeToString(new byte[16]));
        DeviceCloudCredentialService service = new DeviceCloudCredentialService(
                credentialMapper, userDeviceMapper, crypto, huaweiOAuthClient, props, redisTemplate);
        assertFalse(service.isHuaweiCloudReady());
        assertThrows(BizException.class, service::requireHuaweiCloudReady);
    }

    @Test
    void readyWhenEnabledAndKeyed() {
        DeviceCloudProperties props = new DeviceCloudProperties();
        HuaweiCloudProperties huawei = props.getHuawei();
        huawei.setEnabled(true);
        huawei.setClientId("cid");
        huawei.setClientSecret("sec");
        huawei.setRedirectUri("https://example.com/callback");
        TokenCrypto crypto = new TokenCrypto(Base64.getEncoder().encodeToString(new byte[16]));
        DeviceCloudCredentialService service = new DeviceCloudCredentialService(
                credentialMapper, userDeviceMapper, crypto, huaweiOAuthClient, props, redisTemplate);
        assertTrue(service.isHuaweiCloudReady());
    }
}
