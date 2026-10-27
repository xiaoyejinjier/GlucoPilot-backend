package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.JsonNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.DevicePayloadAdapter;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceSyncLog;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import com.threadfit.glucopilot.device.model.UnifiedGlucoseSample;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeviceSyncFacade {

    private final Map<String, DevicePayloadAdapter> adapters;
    private final GlucoseIngestPort glucoseIngestPort;
    private final UserDeviceMapper userDeviceMapper;
    private final DeviceSyncLogMapper deviceSyncLogMapper;

    public DeviceSyncFacade(
            List<DevicePayloadAdapter> adapterList,
            GlucoseIngestPort glucoseIngestPort,
            UserDeviceMapper userDeviceMapper,
            DeviceSyncLogMapper deviceSyncLogMapper) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(
                        a -> a.provider().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a));
        this.glucoseIngestPort = glucoseIngestPort;
        this.userDeviceMapper = userDeviceMapper;
        this.deviceSyncLogMapper = deviceSyncLogMapper;
    }

    @Transactional
    public DeviceDtos.SyncResponse sync(Long userId, DeviceDtos.SyncRequest request) {
        String provider = DeviceProviders.normalize(request.getProvider());
        DevicePayloadAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "未注册的设备厂商适配器: " + provider);
        }
        JsonNode payload = request.getPayload();
        if (payload == null || payload.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "payload 不能为空");
        }
        UserDevice bound = resolveBoundDevice(userId, request.getDeviceSn(), provider);
        List<UnifiedGlucoseSample> samples = adapter.adapt(payload);
        List<DeviceDtos.SyncResultItem> items = new ArrayList<>();
        String remark = "device:" + provider;
        for (UnifiedGlucoseSample sample : samples) {
            GlucoseIngestPort.IngestResult result = glucoseIngestPort.upsertByNode(
                    new GlucoseIngestPort.IngestCommand(
                            userId,
                            sample.recordDate(),
                            sample.nodeCode(),
                            sample.glucoseMmolL(),
                            CodeEnum.GlucoseSourceType.DEVICE.getCode(),
                            remark,
                            sample.recordedAt() != null ? sample.recordedAt() : LocalDateTime.now(),
                            sample.externalId()));
            items.add(DeviceDtos.SyncResultItem.builder()
                    .nodeCode(sample.nodeCode())
                    .recordId(String.valueOf(result.recordId()))
                    .created(result.created())
                    .build());
        }
        if (bound != null) {
            bound.setLastSyncAt(LocalDateTime.now());
            userDeviceMapper.updateById(bound);
            int createdCount = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.getCreated())).count();
            DeviceSyncLog log = new DeviceSyncLog();
            log.setUserId(userId);
            log.setDeviceId(bound.getId());
            log.setProvider(provider);
            log.setSampleCount(items.size());
            log.setCreatedCount(createdCount);
            log.setRemark("sync " + items.size() + " samples, created " + createdCount);
            deviceSyncLogMapper.insert(log);
        }
        return DeviceDtos.SyncResponse.builder()
                .provider(provider)
                .items(items)
                .build();
    }

    private UserDevice resolveBoundDevice(Long userId, String deviceSn, String provider) {
        if (!StringUtils.hasText(deviceSn)) {
            return null;
        }
        UserDevice device = userDeviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getDeviceSn, deviceSn.trim())
                .last("LIMIT 1"));
        if (device == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备不存在或未登记");
        }
        if (device.getUserId() == null || !device.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备未绑定当前用户");
        }
        if (device.getDeviceStatus() == null || device.getDeviceStatus() != 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备已禁用");
        }
        if (StringUtils.hasText(device.getProvider())
                && !provider.equalsIgnoreCase(device.getProvider())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备厂商与请求不一致");
        }
        return device;
    }
}
