package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.common.util.PhoneUtils;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.dto.AdminDeviceDtos;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserProfile;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminDeviceService {

    private final UserDeviceMapper userDeviceMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;

    public PageResult<AdminDeviceDtos.DeviceItem> page(String keyword, Integer deviceStatus, PageQuery query) {
        LambdaQueryWrapper<UserDevice> wrapper = new LambdaQueryWrapper<UserDevice>()
                .eq(deviceStatus != null, UserDevice::getDeviceStatus, deviceStatus)
                .orderByDesc(UserDevice::getGmtCreate)
                .orderByDesc(UserDevice::getId);
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
                .map(d -> toItem(d, accounts.get(d.getUserId()), profiles.get(d.getUserId())))
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
        UserDevice row = new UserDevice();
        row.setDeviceSn(sn);
        row.setDeviceName(request.getDeviceName().trim());
        row.setProvider(resolveProvider(request.getProvider()));
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
        row.setDeviceName(request.getDeviceName().trim());
        row.setProvider(resolveProvider(request.getProvider()));
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

    private AdminDeviceDtos.DeviceItem getItem(Long id) {
        UserDevice row = requireDevice(id);
        UserAccount account = row.getUserId() == null ? null : userAccountMapper.selectById(row.getUserId());
        UserProfile profile = row.getUserId() == null ? null : userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, row.getUserId()).last("LIMIT 1"));
        return toItem(row, account, profile);
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

    private static AdminDeviceDtos.DeviceItem toItem(UserDevice d, UserAccount account, UserProfile profile) {
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
                .userId(d.getUserId() == null ? null : String.valueOf(d.getUserId()))
                .userMasked(userMasked)
                .deviceStatus(d.getDeviceStatus())
                .lastSyncAt(d.getLastSyncAt())
                .createdAt(d.getGmtCreate())
                .build();
    }
}
