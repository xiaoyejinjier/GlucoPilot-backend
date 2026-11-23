package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceCloudCredential;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.service.DeviceSyncFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCloudPullServiceTest {

    @Mock DeviceCloudCredentialService credentialService;
    @Mock HuaweiHealthCloudClient healthCloudClient;
    @Mock DeviceSyncFacade deviceSyncFacade;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    DeviceCloudProperties properties = new DeviceCloudProperties();
    DeviceCloudPullService service;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties.setPullMinIntervalSeconds(60);
        HuaweiCloudProperties huawei = new HuaweiCloudProperties();
        huawei.setEnabled(true);
        properties.setHuawei(huawei);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new DeviceCloudPullService(
                credentialService, healthCloudClient, deviceSyncFacade, properties, redisTemplate);
    }

    @Test
    void disabledCloudRejectsPull() {
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.BIZ_REJECTED, "华为健康云未启用或未配置"))
                .when(credentialService).requireHuaweiCloudReady();
        BizException ex = assertThrows(BizException.class, () -> service.pull(1L, null));
        assertTrue(ex.getMessage().contains("未启用"));
        verify(deviceSyncFacade, never()).sync(anyLong(), any());
    }

    @Test
    void pullUsesAdapterPathAndReturnsItems() {
        doNothing().when(credentialService).requireHuaweiCloudReady();
        UserDevice device = new UserDevice();
        device.setId(9L);
        device.setUserId(1L);
        device.setDeviceSn("HW-001");
        device.setProvider(DeviceProviders.HUAWEI);
        device.setDeviceStatus(1);

        DeviceCloudCredential credential = new DeviceCloudCredential();
        credential.setId(3L);
        credential.setUserId(1L);
        credential.setDeviceId(9L);
        credential.setProvider(DeviceProviders.HUAWEI);
        credential.setStatus(1);

        when(credentialService.requireOwnedHuaweiDevice(1L, 9L)).thenReturn(device);
        when(credentialService.requireActiveCredential(1L, 9L)).thenReturn(credential);
        when(credentialService.requireOwnedDevice(1L, 9L)).thenReturn(device);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(credentialService.resolveAccessToken(credential)).thenReturn("access");

        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("samplePoints");
        when(healthCloudClient.fetchGlucoseSamplePoints("access", 7)).thenReturn(payload);

        DeviceDtos.SyncResponse syncResponse = DeviceDtos.SyncResponse.builder()
                .provider(DeviceProviders.HUAWEI)
                .items(List.of(DeviceDtos.SyncResultItem.builder()
                        .nodeCode("before_breakfast")
                        .recordId("11")
                        .created(true)
                        .build()))
                .build();
        when(deviceSyncFacade.sync(eq(1L), any())).thenReturn(syncResponse);

        DeviceDtos.CloudPullResponse response = service.pull(1L, 9L);
        assertEquals(DeviceProviders.HUAWEI, response.getProvider());
        assertEquals(1, response.getItems().size());
        assertEquals(1, response.getDevices().size());
        assertEquals("9", response.getDevices().getFirst().getDeviceId());

        ArgumentCaptor<DeviceDtos.SyncRequest> captor = ArgumentCaptor.forClass(DeviceDtos.SyncRequest.class);
        verify(deviceSyncFacade).sync(eq(1L), captor.capture());
        assertEquals("HW-001", captor.getValue().getDeviceSn());
        assertEquals(DeviceProviders.HUAWEI, captor.getValue().getProvider());
    }

    @Test
    void throttleBlocksRepeatedPull() {
        doNothing().when(credentialService).requireHuaweiCloudReady();
        UserDevice device = new UserDevice();
        device.setId(9L);
        device.setUserId(1L);
        device.setDeviceSn("HW-001");
        device.setProvider(DeviceProviders.HUAWEI);
        device.setDeviceStatus(1);
        DeviceCloudCredential credential = new DeviceCloudCredential();
        credential.setDeviceId(9L);
        when(credentialService.requireOwnedHuaweiDevice(1L, 9L)).thenReturn(device);
        when(credentialService.requireActiveCredential(1L, 9L)).thenReturn(credential);
        when(credentialService.requireOwnedDevice(1L, 9L)).thenReturn(device);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.pull(1L, 9L));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.getErrorCode());
        verify(healthCloudClient, never()).fetchGlucoseSamplePoints(anyString(), anyInt());
    }
}
