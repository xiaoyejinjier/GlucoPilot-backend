package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceCloudCredential;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.service.DeviceSyncFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceCloudPullService {

    private static final String THROTTLE_KEY = "device:cloud:pull:";

    private final DeviceCloudCredentialService credentialService;
    private final HuaweiHealthCloudClient healthCloudClient;
    private final DeviceSyncFacade deviceSyncFacade;
    private final DeviceCloudProperties cloudProperties;
    private final StringRedisTemplate redisTemplate;

    public DeviceDtos.CloudPullResponse pull(Long userId, Long deviceId) {
        credentialService.requireHuaweiCloudReady();

        List<DeviceCloudCredential> targets = new ArrayList<>();
        if (deviceId != null) {
            UserDevice device = credentialService.requireOwnedHuaweiDevice(userId, deviceId);
            if (!Integer.valueOf(1).equals(device.getDeviceStatus())) {
                throw new BizException(ErrorCode.BIZ_REJECTED, "设备已禁用");
            }
            targets.add(credentialService.requireActiveCredential(userId, deviceId));
        } else {
            targets.addAll(credentialService.listActiveHuawei(userId));
        }

        List<DeviceDtos.SyncResultItem> allItems = new ArrayList<>();
        List<DeviceDtos.CloudPullDeviceSummary> summaries = new ArrayList<>();

        for (DeviceCloudCredential credential : targets) {
            if (credential.getDeviceId() == null) {
                continue;
            }
            UserDevice device = credentialService.requireOwnedDevice(userId, credential.getDeviceId());
            if (!Integer.valueOf(1).equals(device.getDeviceStatus())) {
                continue;
            }
            if (!DeviceProviders.HUAWEI.equals(DeviceProviders.normalize(device.getProvider()))) {
                continue;
            }
            assertNotThrottled(userId, device.getId());

            String accessToken = credentialService.resolveAccessToken(credential);
            JsonNode payload = healthCloudClient.fetchGlucoseSamplePoints(accessToken, 7);

            DeviceDtos.SyncRequest request = new DeviceDtos.SyncRequest();
            request.setProvider(DeviceProviders.HUAWEI);
            request.setDeviceSn(device.getDeviceSn());
            request.setPayload(payload);

            DeviceDtos.SyncResponse syncResponse = deviceSyncFacade.sync(userId, request);
            List<DeviceDtos.SyncResultItem> items = syncResponse.getItems() == null
                    ? List.of()
                    : syncResponse.getItems();
            allItems.addAll(items);
            summaries.add(DeviceDtos.CloudPullDeviceSummary.builder()
                    .deviceId(String.valueOf(device.getId()))
                    .provider(DeviceProviders.HUAWEI)
                    .itemCount(items.size())
                    .build());
            markThrottled(userId, device.getId());
        }

        return DeviceDtos.CloudPullResponse.builder()
                .provider(DeviceProviders.HUAWEI)
                .items(allItems)
                .devices(summaries)
                .build();
    }

    private void assertNotThrottled(Long userId, Long deviceId) {
        int seconds = Math.max(1, cloudProperties.getPullMinIntervalSeconds());
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(THROTTLE_KEY + userId + ":" + deviceId, "1", Duration.ofSeconds(seconds));
        if (Boolean.FALSE.equals(ok)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "云同步过于频繁，请稍后再试");
        }
    }

    private void markThrottled(Long userId, Long deviceId) {
        // setIfAbsent already set TTL on success path; no-op if key exists
        if (!StringUtils.hasText(redisTemplate.opsForValue().get(THROTTLE_KEY + userId + ":" + deviceId))) {
            int seconds = Math.max(1, cloudProperties.getPullMinIntervalSeconds());
            redisTemplate.opsForValue()
                    .set(THROTTLE_KEY + userId + ":" + deviceId, "1", Duration.ofSeconds(seconds));
        }
    }
}
