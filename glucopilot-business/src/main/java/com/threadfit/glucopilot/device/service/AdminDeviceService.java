package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.device.adapter.support.DeviceCategories;
import com.threadfit.glucopilot.device.adapter.support.DeviceCategoryHints;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.cloud.DeviceCloudCredentialService;
import com.threadfit.glucopilot.device.dto.AdminDeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceCloudCredential;
import com.threadfit.glucopilot.device.entity.DeviceSyncLog;
import com.threadfit.glucopilot.device.entity.DeviceSyncSample;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.DeviceSyncSampleMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端设备档案服务：分页查询 / 新增 / 修改 / 启停 / 软删除 / 同步流水 / 云授权。
 */
@Service
@RequiredArgsConstructor
public class AdminDeviceService {

    private static final long STALE_HOURS = 48;

    private final UserDeviceMapper userDeviceMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final DeviceSyncLogMapper deviceSyncLogMapper;
    private final DeviceSyncSampleMapper deviceSyncSampleMapper;
    private final DeviceCloudCredentialService deviceCloudCredentialService;

    /**
     * 管理端分页查询设备。
     *
     * @param keyword      SN/名称模糊
     * @param deviceStatus 启停
     * @param provider     厂商
     * @param bound        true=已绑定 false=未绑定
     * @param cloudLinked  true=已云授权 false=未云授权
     */
    public PageResult<AdminDeviceDtos.DeviceItem> page(
            String keyword,
            Integer deviceStatus,
            String provider,
            String deviceCategory,
            Boolean bound,
            Boolean cloudLinked,
            PageQuery query) {
        LambdaQueryWrapper<UserDevice> wrapper = new LambdaQueryWrapper<UserDevice>()
                .eq(deviceStatus != null, UserDevice::getDeviceStatus, deviceStatus)
                .orderByDesc(UserDevice::getGmtCreate)
                .orderByDesc(UserDevice::getId);

        if (StringUtils.hasText(provider)) {
            wrapper.eq(UserDevice::getProvider, DeviceProviders.normalize(provider));
        }
        if (StringUtils.hasText(deviceCategory)) {
            wrapper.eq(UserDevice::getDeviceCategory, DeviceCategories.normalize(deviceCategory));
        }
        if (bound != null) {
            if (bound) {
                wrapper.isNotNull(UserDevice::getUserId);
            } else {
                wrapper.isNull(UserDevice::getUserId);
            }
        }
        if (cloudLinked != null) {
            Set<Long> linkedIds = deviceCloudCredentialService.allLinkedDeviceIds();
            if (cloudLinked) {
                if (linkedIds.isEmpty()) {
                    return PageResult.of(query.getPageNum(), query.getPageSize(), 0, List.of());
                }
                wrapper.in(UserDevice::getId, linkedIds);
            } else if (!linkedIds.isEmpty()) {
                wrapper.notIn(UserDevice::getId, linkedIds);
            }
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(UserDevice::getDeviceSn, kw).or().like(UserDevice::getDeviceName, kw));
        }

        Page<UserDevice> rows = userDeviceMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        List<Long> userIds = rows.getRecords().stream()
                .map(UserDevice::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> deviceIds = rows.getRecords().stream().map(UserDevice::getId).toList();
        Set<Long> linkedOnPage = deviceCloudCredentialService.linkedDeviceIds(deviceIds);

        Map<Long, UserAccount> accounts = userIds.isEmpty()
                ? Collections.emptyMap()
                : userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .in(UserAccount::getId, userIds)).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));

        Map<Long, UserProfile> profiles = userIds.isEmpty()
                ? Collections.emptyMap()
                : userProfileMapper.selectList(new LambdaQueryWrapper<UserProfile>()
                        .in(UserProfile::getUserId, userIds)).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity(), (a, b) -> a));

        List<AdminDeviceDtos.DeviceItem> items = rows.getRecords().stream()
                .map(d -> toItem(
                        d,
                        accounts.get(d.getUserId()),
                        profiles.get(d.getUserId()),
                        linkedOnPage.contains(d.getId())))
                .toList();
        return PageResult.of(rows.getCurrent(), rows.getSize(), rows.getTotal(), items);
    }

    @Transactional
    public AdminDeviceDtos.DeviceItem create(AdminDeviceDtos.CreateRequest request) {
        String sn = request.getDeviceSn().trim();
        UserDevice occupied = userDeviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getDeviceSn, sn)
                .last("LIMIT 1"));
        if (occupied != null) {
            throw new BizException(ErrorCode.CONFLICT, "设备序列号已被占用");
        }
        Long userId = normalizeUserId(request.getUserId());
        if (userId != null) {
            requireUser(userId);
        }
        String name = request.getDeviceName().trim();
        String model = normalizeModel(request.getDeviceModel());
        UserDevice row = new UserDevice();
        row.setDeviceSn(sn);
        row.setDeviceName(name);
        row.setProvider(resolveProvider(request.getProvider()));
        row.setDeviceCategory(DeviceCategoryHints.resolveOrSuggest(
                DeviceCategories.normalize(request.getDeviceCategory()), sn, name, model));
        row.setDeviceModel(model);
        row.setUserId(userId);
        row.setDeviceStatus(1);
        userDeviceMapper.insert(row);
        return getItem(row.getId());
    }

    @Transactional
    public AdminDeviceDtos.DeviceItem update(Long id, AdminDeviceDtos.UpdateRequest request) {
        UserDevice row = requireDevice(id);
        Long userId = normalizeUserId(request.getUserId());
        if (userId != null) {
            requireUser(userId);
        }
        String name = request.getDeviceName().trim();
        String model = normalizeModel(request.getDeviceModel());
        row.setDeviceName(name);
        row.setProvider(resolveProvider(request.getProvider()));
        row.setDeviceCategory(DeviceCategoryHints.resolveOrSuggest(
                DeviceCategories.normalize(request.getDeviceCategory()), row.getDeviceSn(), name, model));
        row.setDeviceModel(model);
        row.setUserId(userId);
        userDeviceMapper.updateById(row);
        return getItem(id);
    }

    @Transactional
    public AdminDeviceDtos.UpdatedResult updateStatus(Long id, Integer deviceStatus) {
        UserDevice row = requireDevice(id);
        row.setDeviceStatus(deviceStatus);
        userDeviceMapper.updateById(row);
        return AdminDeviceDtos.UpdatedResult.builder().updated(true).build();
    }

    @Transactional
    public void remove(Long id) {
        UserDevice row = requireDevice(id);
        SoftDeletes.mark(
                userDeviceMapper,
                UserDevice::getId,
                row.getId(),
                UserDevice::getIsDeleted,
                UserDevice::getDeletedAt,
                UserDevice::getDeletedBy);
    }

    public List<AdminDeviceDtos.SyncLogItem> listSyncLogs(Long deviceId, int limit) {
        requireDevice(deviceId);
        int size = Math.min(Math.max(limit, 1), 50);
        return deviceSyncLogMapper.selectList(new LambdaQueryWrapper<DeviceSyncLog>()
                        .eq(DeviceSyncLog::getDeviceId, deviceId)
                        .orderByDesc(DeviceSyncLog::getGmtCreate)
                        .orderByDesc(DeviceSyncLog::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(log -> AdminDeviceDtos.SyncLogItem.builder()
                        .id(String.valueOf(log.getId()))
                        .provider(log.getProvider())
                        .sampleCount(log.getSampleCount())
                        .createdCount(log.getCreatedCount())
                        .remark(log.getRemark())
                        .syncedAt(log.getGmtCreate())
                        .build())
                .toList();
    }

    public AdminDeviceDtos.SyncSamplesResponse listSyncSamples(Long deviceId, Long logId) {
        requireDevice(deviceId);
        DeviceSyncLog log = deviceSyncLogMapper.selectById(logId);
        if (log == null || !deviceId.equals(log.getDeviceId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "同步流水不存在");
        }
        List<AdminDeviceDtos.SyncSamplePoint> points = deviceSyncSampleMapper.selectList(
                        new LambdaQueryWrapper<DeviceSyncSample>()
                                .eq(DeviceSyncSample::getSyncLogId, logId)
                                .eq(DeviceSyncSample::getDeviceId, deviceId)
                                .orderByAsc(DeviceSyncSample::getRecordedAt)
                                .orderByAsc(DeviceSyncSample::getId))
                .stream()
                .map(s -> AdminDeviceDtos.SyncSamplePoint.builder()
                        .recordedAt(s.getRecordedAt())
                        .nodeCode(s.getNodeCode())
                        .glucoseValue(s.getGlucoseValue())
                        .build())
                .toList();
        return AdminDeviceDtos.SyncSamplesResponse.builder()
                .syncedAt(log.getGmtCreate())
                .sampleCount(log.getSampleCount() == null ? points.size() : log.getSampleCount())
                .points(points)
                .build();
    }

    public AdminDeviceDtos.CloudAuthStatus cloudAuth(Long deviceId) {
        requireDevice(deviceId);
        DeviceCloudCredential row = deviceCloudCredentialService.findActiveByDeviceId(deviceId);
        if (row == null) {
            return AdminDeviceDtos.CloudAuthStatus.builder().linked(false).build();
        }
        return AdminDeviceDtos.CloudAuthStatus.builder()
                .linked(true)
                .expiresAt(row.getTokenExpiresAt())
                .scopes(row.getScopes())
                .vendorSubjectMasked(maskSubject(row.getVendorSubject()))
                .build();
    }

    @Transactional
    public void revokeCloudAuth(Long deviceId) {
        requireDevice(deviceId);
        deviceCloudCredentialService.revokeByDeviceId(deviceId);
    }

    private AdminDeviceDtos.DeviceItem getItem(Long id) {
        UserDevice row = requireDevice(id);
        UserAccount account = row.getUserId() == null ? null : userAccountMapper.selectById(row.getUserId());
        UserProfile profile = row.getUserId() == null ? null : userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, row.getUserId()).last("LIMIT 1"));
        boolean linked = deviceCloudCredentialService.findActiveByDeviceId(id) != null;
        return toItem(row, account, profile, linked);
    }

    private UserDevice requireDevice(Long id) {
        UserDevice row = userDeviceMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        return row;
    }

    private void requireUser(Long userId) {
        if (userAccountMapper.selectById(userId) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "绑定用户不存在");
        }
    }

    private static Long normalizeUserId(Long userId) {
        return userId == null || userId <= 0 ? null : userId;
    }

    private static String resolveProvider(String provider) {
        return DeviceProviders.normalize(provider);
    }

    private static String normalizeModel(String model) {
        if (!StringUtils.hasText(model)) {
            return null;
        }
        return model.trim();
    }

    static String syncHealthOf(LocalDateTime lastSyncAt) {
        if (lastSyncAt == null) {
            return "NEVER";
        }
        if (lastSyncAt.isBefore(LocalDateTime.now().minusHours(STALE_HOURS))) {
            return "STALE";
        }
        return "OK";
    }

    static String maskSubject(String subject) {
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        String s = subject.trim();
        if (s.length() <= 4) {
            return "****";
        }
        if (s.length() <= 8) {
            return s.substring(0, 2) + "****";
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 2);
    }

    private static AdminDeviceDtos.DeviceItem toItem(
            UserDevice d, UserAccount account, UserProfile profile, boolean cloudLinked) {
        String userMasked = "未绑定";
        if (account != null) {
            if (StringUtils.hasText(account.getPhone())) {
                userMasked = PhoneUtils.mask(account.getPhone());
            } else if (profile != null && StringUtils.hasText(profile.getNickname())) {
                userMasked = profile.getNickname();
            } else if (StringUtils.hasText(account.getUsername())) {
                userMasked = account.getUsername();
            } else {
                userMasked = "用户#" + account.getId();
            }
        }
        return AdminDeviceDtos.DeviceItem.builder()
                .id(String.valueOf(d.getId()))
                .deviceSn(d.getDeviceSn())
                .deviceName(d.getDeviceName())
                .provider(d.getProvider())
                .deviceCategory(d.getDeviceCategory())
                .deviceModel(d.getDeviceModel())
                .userId(d.getUserId() == null ? null : String.valueOf(d.getUserId()))
                .userMasked(userMasked)
                .deviceStatus(d.getDeviceStatus())
                .lastSyncAt(d.getLastSyncAt())
                .createdAt(d.getGmtCreate())
                .cloudLinked(cloudLinked)
                .syncHealth(syncHealthOf(d.getLastSyncAt()))
                .build();
    }
}
