package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceCategories;
import com.threadfit.glucopilot.device.adapter.support.DeviceCategoryHints;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.cloud.DeviceCloudCredentialService;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceSyncLog;
import com.threadfit.glucopilot.device.entity.DeviceSyncSample;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.DeviceSyncSampleMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Set;

/**
 * 用户端设备档案服务：列表 / 自助绑定 / 解绑 / 同步流水查询。
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>唯一识别设备靠 {@code deviceSn}，适配器路由靠 {@code provider}；</li>
 *   <li>解绑只清空 {@code user_id}（保留档案），不物理删除记录；</li>
 *   <li>绑定采用「先查后写 + 唯一键兜底」的方式处理并发。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserDeviceService {

    /** 用户设备表 Mapper。 */
    private final UserDeviceMapper userDeviceMapper;

    /** 设备同步日志 Mapper。 */
    private final DeviceSyncLogMapper deviceSyncLogMapper;

    /** 同步血糖样本快照 Mapper。 */
    private final DeviceSyncSampleMapper deviceSyncSampleMapper;

    private final DeviceCloudCredentialService deviceCloudCredentialService;

    /**
     * 查询当前用户绑定的设备列表。
     *
     * <p>按最后同步时间倒序、再按 ID 倒序，最近活跃的设备排前面。</p>
     *
     * @param userId 当前用户 ID
     * @return 设备列表（已转成 DTO）
     */
    public List<DeviceDtos.DeviceItem> listMine(Long userId) {
        List<UserDevice> rows = userDeviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getUserId, userId)
                .orderByDesc(UserDevice::getLastSyncAt)
                .orderByDesc(UserDevice::getId));
        Set<Long> linked = deviceCloudCredentialService.linkedDeviceIds(
                userId, rows.stream().map(UserDevice::getId).toList());
        return rows.stream().map(d -> toItem(d, linked.contains(d.getId()))).toList();
    }

    /**
     * 绑定设备（自助认领）。
     *
     * <p>处理逻辑：</p>
     * <ol>
     *   <li>规范化 deviceSn、provider，确定设备名（未传则按厂商给默认名）；</li>
     *   <li>按 deviceSn 查档案，并用 {@code FOR UPDATE} 行锁防并发；</li>
     *   <li>无档案 → 新建；有档案 → 校验归属与状态后更新或认领。</li>
     * </ol>
     *
     * @param userId  当前用户 ID
     * @param request 绑定请求（deviceSn、provider、可选 deviceName）
     * @return 绑定后的设备 DTO
     */
    @Transactional
    public DeviceDtos.DeviceItem bind(Long userId, DeviceDtos.BindRequest request) {
        // 规范化输入：SN 去空格，provider 走白名单校验（空值默认 MOCK）
        String sn = request.getDeviceSn().trim();
        String provider = DeviceProviders.normalize(request.getProvider());
        // 设备名：传了用传入值，没传按厂商给默认名
        String name = StringUtils.hasText(request.getDeviceName())
                ? request.getDeviceName().trim()
                : defaultName(provider);
        String category = DeviceCategoryHints.resolveOrSuggest(
                DeviceCategories.normalize(request.getDeviceCategory()), sn, name,
                StringUtils.hasText(request.getDeviceModel()) ? request.getDeviceModel().trim() : null);
        String model = StringUtils.hasText(request.getDeviceModel()) ? request.getDeviceModel().trim() : null;

        // 按 SN 查档案并加行锁（LIMIT 1 FOR UPDATE），防止并发绑定同一设备
        UserDevice existing = userDeviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getDeviceSn, sn)
                .last("LIMIT 1 FOR UPDATE"));

        // 情况一：档案不存在 → 新建并直接绑定到当前用户
        if (existing == null) {
            UserDevice row = new UserDevice();
            row.setDeviceSn(sn);
            row.setDeviceName(name);
            row.setProvider(provider);
            row.setDeviceCategory(category);
            row.setDeviceModel(model);
            row.setUserId(userId);
            row.setDeviceStatus(1);
            try {
                userDeviceMapper.insert(row);
            } catch (DuplicateKeyException conflict) {
                // 并发下另一个事务已插入同一 SN，唯一键冲突 → 提示刷新重试
                throw new BizException(ErrorCode.CONFLICT, "设备序列号已被占用，请刷新后重试");
            }
            return toItem(row, false);
        }

        // 情况二：档案已被其他用户绑定 → 拒绝
        if (existing.getUserId() != null && !existing.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.CONFLICT, "设备序列号已被其他用户绑定");
        }
        // 档案已停用 → 拒绝（需管理员处理）
        if (!Integer.valueOf(1).equals(existing.getDeviceStatus())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备已停用，请联系管理员");
        }
        // 情况三：已是本人设备 → 允许更新名称与厂商（以本次请求为准）
        if (existing.getUserId() != null && existing.getUserId().equals(userId)) {
            existing.setDeviceName(name);
            existing.setProvider(provider);
            if (category != null) {
                existing.setDeviceCategory(category);
            } else if (!StringUtils.hasText(existing.getDeviceCategory())) {
                String hinted = DeviceCategoryHints.suggest(sn, name, model);
                if (hinted != null) {
                    existing.setDeviceCategory(hinted);
                }
            }
            if (model != null) {
                existing.setDeviceModel(model);
            }
            userDeviceMapper.updateById(existing);
            return toItem(existing, isCloudLinked(userId, existing.getId()));
        }
        // 情况四：档案空闲（user_id 为空）→ 认领到当前用户
        existing.setUserId(userId);
        existing.setDeviceName(name);
        existing.setProvider(provider);
        if (category != null) {
            existing.setDeviceCategory(category);
        } else if (!StringUtils.hasText(existing.getDeviceCategory())) {
            String hinted = DeviceCategoryHints.suggest(sn, name, model);
            if (hinted != null) {
                existing.setDeviceCategory(hinted);
            }
        }
        if (model != null) {
            existing.setDeviceModel(model);
        }
        existing.setDeviceStatus(1);
        userDeviceMapper.updateById(existing);
        return toItem(existing, false);
    }

    private boolean isCloudLinked(Long userId, Long deviceId) {
        return deviceCloudCredentialService.findActiveByDevice(userId, deviceId) != null;
    }

    /**
     * 解绑设备：仅清空归属，不删除档案。
     *
     * <p>先校验存在性与归属，再用带条件的 UPDATE 做「乐观并发控制」：
     * 更新行数不为 1 说明归属在期间被改动，提示刷新重试。</p>
     *
     * @param userId   当前用户 ID
     * @param deviceId 设备 ID
     */
    public void unbind(Long userId, Long deviceId) {
        UserDevice row = userDeviceMapper.selectById(deviceId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        // 只能解绑本人设备
        if (row.getUserId() == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "只能解绑本人设备");
        }
        // 条件更新：仍属于本人时才置空 user_id，同时记录更新人与时间
        int changed = userDeviceMapper.update(null, new LambdaUpdateWrapper<UserDevice>()
                .eq(UserDevice::getId, deviceId).eq(UserDevice::getUserId, userId)
                .set(UserDevice::getUserId, null)
                .set(UserDevice::getUpdatedBy, userId)
                .set(UserDevice::getGmtModified, java.time.LocalDateTime.now()));
        // 受影响行数不是 1：说明归属已变化（并发解绑/被他人认领），提示刷新
        if (changed != 1) throw new BizException(ErrorCode.CONFLICT, "设备归属已变更，请刷新后重试");
    }

    /**
     * 账号注销：物理删除该用户的设备绑定行。
     *
     * <p>与自助解绑刻意不同：解绑只把 user_id 置空（设备档案留着给别人认领）；
     * 注销则是「这个人不能再被定位」，行里还有 last_sync_at 等使用痕迹，所以直接删行。</p>
     */
    @Transactional
    public void purgeAccountData(Long userId) {
        userDeviceMapper.purgeByUserId(userId);
    }

    /**
     * 查询本人某设备近 N 次同步流水（默认 10，限制在 1~50 之间）。
     *
     * @param userId   当前用户 ID
     * @param deviceId 设备 ID
     * @param limit    期望条数（会被夹到 1~50）
     * @return 同步日志列表（按创建时间、ID 倒序）
     */
    public List<DeviceDtos.SyncLogItem> listSyncLogs(Long userId, Long deviceId, int limit) {
        UserDevice row = userDeviceMapper.selectById(deviceId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        // 只能查看本人设备的同步记录
        if (row.getUserId() == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "只能查看本人设备同步记录");
        }
        // 条数夹取：避免 limit 过大或为负
        int size = Math.min(Math.max(limit, 1), 50);
        return deviceSyncLogMapper.selectList(new LambdaQueryWrapper<DeviceSyncLog>()
                        .eq(DeviceSyncLog::getDeviceId, deviceId)
                        .eq(DeviceSyncLog::getUserId, userId)
                        .orderByDesc(DeviceSyncLog::getGmtCreate)
                        .orderByDesc(DeviceSyncLog::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(UserDeviceService::toSyncLogItem)
                .toList();
    }

    /**
     * 本人某次同步的血糖样本快照（只读），供小程序可视化曲线。
     *
     * <p>校验设备归属与流水归属；旧流水无快照时 {@code points} 为空数组。
     */
    public DeviceDtos.SyncSamplesResponse listSyncSamples(Long userId, Long deviceId, Long logId) {
        UserDevice row = userDeviceMapper.selectById(deviceId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        if (row.getUserId() == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "只能查看本人设备同步记录");
        }
        DeviceSyncLog log = deviceSyncLogMapper.selectById(logId);
        if (log == null
                || !deviceId.equals(log.getDeviceId())
                || log.getUserId() == null
                || !log.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "同步流水不存在");
        }
        List<DeviceDtos.SyncSamplePoint> points =
                deviceSyncSampleMapper
                        .selectList(
                                new LambdaQueryWrapper<DeviceSyncSample>()
                                        .eq(DeviceSyncSample::getSyncLogId, logId)
                                        .eq(DeviceSyncSample::getDeviceId, deviceId)
                                        .eq(DeviceSyncSample::getUserId, userId)
                                        .orderByAsc(DeviceSyncSample::getRecordedAt)
                                        .orderByAsc(DeviceSyncSample::getId))
                        .stream()
                        .map(
                                sample ->
                                        DeviceDtos.SyncSamplePoint.builder()
                                                .recordedAt(sample.getRecordedAt())
                                                .nodeCode(sample.getNodeCode())
                                                .glucoseValue(sample.getGlucoseValue())
                                                .build())
                        .toList();
        return DeviceDtos.SyncSamplesResponse.builder()
                .syncedAt(log.getGmtCreate())
                .sampleCount(log.getSampleCount() == null ? points.size() : log.getSampleCount())
                .points(points)
                .build();
    }

    /** 同步日志实体 → DTO。 */
    private static DeviceDtos.SyncLogItem toSyncLogItem(DeviceSyncLog log) {
        return DeviceDtos.SyncLogItem.builder()
                .id(String.valueOf(log.getId()))
                .provider(log.getProvider())
                .sampleCount(log.getSampleCount())
                .createdCount(log.getCreatedCount())
                .remark(log.getRemark())
                .syncedAt(log.getGmtCreate())
                .build();
    }

    /** 按厂商生成默认设备名。 */
    private static String defaultName(String provider) {
        return switch (provider) {
            case DeviceProviders.HUAWEI -> "华为健康设备";
            case DeviceProviders.APPLE_HEALTH -> "苹果健康设备";
            case DeviceProviders.HEALTH_CONNECT -> "Health Connect 设备";
            case DeviceProviders.NIGHTSCOUT -> "Nightscout 设备";
            default -> "我的血糖仪";
        };
    }

    /** 设备实体 → DTO；列表场景下返回的都是已绑定设备，故 bound 固定为 true。 */
    private static DeviceDtos.DeviceItem toItem(UserDevice d, boolean cloudLinked) {
        return DeviceDtos.DeviceItem.builder()
                .id(String.valueOf(d.getId()))
                .deviceSn(d.getDeviceSn())
                .deviceName(d.getDeviceName())
                .provider(d.getProvider())
                .deviceCategory(d.getDeviceCategory())
                .deviceModel(d.getDeviceModel())
                .deviceStatus(d.getDeviceStatus())
                .lastSyncAt(d.getLastSyncAt())
                .bound(Boolean.TRUE)
                .cloudLinked(cloudLinked)
                .build();
    }
}