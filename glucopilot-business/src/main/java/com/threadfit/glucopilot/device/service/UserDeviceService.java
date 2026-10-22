package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

/**
 * 用户端设备档案：列表 / 自助绑定 / 解绑。
 * 唯一认设备靠 deviceSn；选适配器用 provider。解绑只清空 user_id，不删档案。
 */
@Service
@RequiredArgsConstructor
public class UserDeviceService {

    private final UserDeviceMapper userDeviceMapper;

    public List<DeviceDtos.DeviceItem> listMine(Long userId) {
        return userDeviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                        .eq(UserDevice::getUserId, userId)
                        .orderByDesc(UserDevice::getLastSyncAt)
                        .orderByDesc(UserDevice::getId))
                .stream()
                .map(UserDeviceService::toItem)
                .toList();
    }

    @Transactional
    public DeviceDtos.DeviceItem bind(Long userId, DeviceDtos.BindRequest request) {
        String sn = request.getDeviceSn().trim();
        String provider = DeviceProviders.normalize(request.getProvider());
        String name = StringUtils.hasText(request.getDeviceName())
                ? request.getDeviceName().trim()
                : defaultName(provider);

        UserDevice existing = userDeviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getDeviceSn, sn)
                .last("LIMIT 1 FOR UPDATE"));
        if (existing == null) {
            UserDevice row = new UserDevice();
            row.setDeviceSn(sn);
            row.setDeviceName(name);
            row.setProvider(provider);
            row.setUserId(userId);
            row.setDeviceStatus(1);
            try {
                userDeviceMapper.insert(row);
            } catch (DuplicateKeyException conflict) {
                throw new BizException(ErrorCode.CONFLICT, "设备序列号已被占用，请刷新后重试");
            }
            return toItem(row);
        }
        if (existing.getUserId() != null && !existing.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.CONFLICT, "设备序列号已被其他用户绑定");
        }
        if (!Integer.valueOf(1).equals(existing.getDeviceStatus())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备已停用，请联系管理员");
        }
        if (existing.getUserId() != null && existing.getUserId().equals(userId)) {
            // 已是本人设备：允许更新名称与厂商（厂商不一致时以本次为准）
            existing.setDeviceName(name);
            existing.setProvider(provider);
            userDeviceMapper.updateById(existing);
            return toItem(existing);
        }
        if (existing.getUserId() != null) {
            throw new BizException(ErrorCode.CONFLICT, "设备序列号已被其他用户绑定");
        }
        // 空闲档案：认领
        existing.setUserId(userId);
        existing.setDeviceName(name);
        existing.setProvider(provider);
        existing.setDeviceStatus(1);
        userDeviceMapper.updateById(existing);
        return toItem(existing);
    }

    @Transactional
    public void unbind(Long userId, Long deviceId) {
        UserDevice row = userDeviceMapper.selectById(deviceId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        if (row.getUserId() == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "只能解绑本人设备");
        }
        int changed = userDeviceMapper.update(null, new LambdaUpdateWrapper<UserDevice>()
                .eq(UserDevice::getId, deviceId).eq(UserDevice::getUserId, userId)
                .set(UserDevice::getUserId, null)
                .set(UserDevice::getUpdatedBy, userId)
                .set(UserDevice::getGmtModified, java.time.LocalDateTime.now()));
        if (changed != 1) throw new BizException(ErrorCode.CONFLICT, "设备归属已变更，请刷新后重试");
    }

    private static String defaultName(String provider) {
        return switch (provider) {
            case DeviceProviders.HUAWEI -> "华为健康设备";
            case DeviceProviders.APPLE_HEALTH -> "苹果健康设备";
            case DeviceProviders.HEALTH_CONNECT -> "Health Connect 设备";
            case DeviceProviders.NIGHTSCOUT -> "Nightscout 设备";
            default -> "我的血糖仪";
        };
    }

    private static DeviceDtos.DeviceItem toItem(UserDevice d) {
        return DeviceDtos.DeviceItem.builder()
                .id(String.valueOf(d.getId()))
                .deviceSn(d.getDeviceSn())
                .deviceName(d.getDeviceName())
                .provider(d.getProvider())
                .deviceStatus(d.getDeviceStatus())
                .lastSyncAt(d.getLastSyncAt())
                .bound(Boolean.TRUE)
                .build();
    }
}
