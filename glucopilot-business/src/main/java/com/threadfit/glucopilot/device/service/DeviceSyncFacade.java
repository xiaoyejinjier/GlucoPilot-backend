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

/**
 * 设备数据同步门面（Facade）。
 *
 * <p>职责：接收客户端/设备上报的同步请求，按 provider 路由到对应的
 * {@link DevicePayloadAdapter} 解析厂商原始报文，转成统一血糖样本后
 * 通过 {@link GlucoseIngestPort} 幂等入库，并记录设备最后同步时间与同步日志。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>策略模式：所有适配器由 Spring 注入为 List，构造时按 provider 建成 Map，实现 O(1) 路由；</li>
 *   <li>新增厂商只需新增一个 {@link DevicePayloadAdapter} 实现 Bean，本类无需改动；</li>
 *   <li>整个同步过程在一个事务内完成，保证入库、设备更新时间、日志的一致性。</li>
 * </ul>
 */
@Service
public class DeviceSyncFacade {

    /** 适配器注册表：key = 大写 provider，value = 对应适配器实例。构造后只读。 */
    private final Map<String, DevicePayloadAdapter> adapters;

    /** 血糖数据入库端口（端口-适配器架构中的 port，屏蔽具体存储实现）。 */
    private final GlucoseIngestPort glucoseIngestPort;

    /** 用户设备表 Mapper，用于查绑定设备、更新最后同步时间。 */
    private final UserDeviceMapper userDeviceMapper;

    /** 设备同步日志 Mapper。 */
    private final DeviceSyncLogMapper deviceSyncLogMapper;

    /**
     * 构造器注入。
     *
     * <p>Spring 会把所有 {@link DevicePayloadAdapter} 实现注入为 {@code adapterList}，
     * 这里在 Bean 初始化时一次性建成「provider -> 适配器」的 Map。</p>
     *
     * <p>注意：注册侧 key 用 {@code toUpperCase(Locale.ROOT)}，消费侧
     * {@link DeviceProviders#normalize(String)} 必须保持同样的规范化规则，
     * 否则会出现「已注册但查不到」的问题。</p>
     *
     * @param adapterList           所有厂商适配器实现（由 Spring 自动收集）
     * @param glucoseIngestPort     血糖入库端口
     * @param userDeviceMapper      用户设备 Mapper
     * @param deviceSyncLogMapper   同步日志 Mapper
     */
    public DeviceSyncFacade(
            List<DevicePayloadAdapter> adapterList,
            GlucoseIngestPort glucoseIngestPort,
            UserDeviceMapper userDeviceMapper,
            DeviceSyncLogMapper deviceSyncLogMapper) {
        // 将适配器列表转为 Map：key 统一大写，value 为适配器本身；
        // 若两个适配器声明了同一 provider，保留先出现的（(a, b) -> a），避免 toMap 抛重复 key 异常。
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(
                        a -> a.provider().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a));
        this.glucoseIngestPort = glucoseIngestPort;
        this.userDeviceMapper = userDeviceMapper;
        this.deviceSyncLogMapper = deviceSyncLogMapper;
    }

    /**
     * 执行一次设备数据同步。
     *
     * <p>流程：规范化 provider → 查适配器 → 校验 payload → 解析设备绑定（可选）
     * → 适配器解析出统一样本 → 逐条幂等入库 → 若有绑定设备则刷新 last_sync_at 并写同步日志
     * → 返回逐条同步结果。</p>
     *
     * @param userId  当前登录用户 ID（数据归属）
     * @param request 同步请求（provider、可选 deviceSn、原始 payload）
     * @return 同步结果，包含规范化后的 provider 和逐条样本入库结果
     */
    @Transactional
    public DeviceDtos.SyncResponse sync(Long userId, DeviceDtos.SyncRequest request) {
        // 1. 规范化 provider（trim + 大写 + 白名单校验，空值默认 MOCK）
        String provider = DeviceProviders.normalize(request.getProvider());

        // 2. 按 provider 路由到对应适配器；未注册则拒绝
        DevicePayloadAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "未注册的设备厂商适配器: " + provider);
        }

        // 3. 校验 payload：既排除 Java null，也排除 JSON null（"payload": null）
        JsonNode payload = request.getPayload();
        if (payload == null || payload.isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "payload 不能为空");
        }

        // 4. 解析并校验绑定设备（deviceSn 可选；不传则返回 null，跳过设备相关逻辑）
        UserDevice bound = resolveBoundDevice(userId, request.getDeviceSn(), provider);

        // 5. 交给适配器把厂商原始报文解析成统一的血糖样本列表
        List<UnifiedGlucoseSample> samples = adapter.adapt(payload);

        // 逐条入库结果，用于统计和回填响应
        List<DeviceDtos.SyncResultItem> items = new ArrayList<>();
        // 数据来源备注，标记为设备来源及具体厂商
        String remark = "device:" + provider;

        // 6. 逐条样本幂等入库（upsertByNode 按节点 + 外部 ID 做幂等）
        for (UnifiedGlucoseSample sample : samples) {
            GlucoseIngestPort.IngestResult result = glucoseIngestPort.upsertByNode(
                    new GlucoseIngestPort.IngestCommand(
                            userId,
                            sample.recordDate(),
                            sample.nodeCode(),
                            sample.glucoseMmolL(),
                            CodeEnum.GlucoseSourceType.DEVICE.getCode(),
                            remark,
                            // 记录时间缺失时用当前时间兜底
                            sample.recordedAt() != null ? sample.recordedAt() : LocalDateTime.now(),
                            sample.externalId()));
            // 收集每条样本的入库结果：节点、记录 ID、是否新建
            items.add(DeviceDtos.SyncResultItem.builder()
                    .nodeCode(sample.nodeCode())
                    .recordId(String.valueOf(result.recordId()))
                    .created(result.created())
                    .build());
        }

        // 7. 仅当请求指定了绑定设备时，才刷新最后同步时间并写同步日志
        if (bound != null) {
            bound.setLastSyncAt(LocalDateTime.now());
            userDeviceMapper.updateById(bound);

            // 统计新增条数（created 为 Boolean，用 Boolean.TRUE.equals 防 NPE）
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

        // 8. 回填响应：规范化后的 provider + 逐条结果
        return DeviceDtos.SyncResponse.builder()
                .provider(provider)
                .items(items)
                .build();
    }

    /**
     * 解析并校验请求指定的绑定设备。
     *
     * <p>deviceSn 为空时直接返回 null（表示本次同步不绑定具体设备）。
     * 否则依次校验：设备存在、归属当前用户、未被禁用、厂商与请求一致。</p>
     *
     * @param userId   当前用户 ID
     * @param deviceSn 设备序列号（可选）
     * @param provider 已规范化的厂商名
     * @return 校验通过的设备；未传 deviceSn 时返回 null
     */
    private UserDevice resolveBoundDevice(Long userId, String deviceSn, String provider) {
        // 未传设备序列号：视为不绑定设备，跳过后续校验
        if (!StringUtils.hasText(deviceSn)) {
            return null;
        }
        // 按设备序列号查询（LIMIT 1 防止多条时 selectOne 抛异常）
        UserDevice device = userDeviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getDeviceSn, deviceSn.trim())
                .last("LIMIT 1"));
        // 设备不存在或未登记
        if (device == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备不存在或未登记");
        }
        // 设备未绑定当前用户（含 userId 为空的脏数据）
        if (device.getUserId() == null || !device.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备未绑定当前用户");
        }
        // 设备已禁用（deviceStatus != 1）
        if (device.getDeviceStatus() == null || device.getDeviceStatus() != 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备已禁用");
        }
        // 设备登记了 provider 时，必须与请求厂商一致（忽略大小写）
        if (StringUtils.hasText(device.getProvider())
                && !provider.equalsIgnoreCase(device.getProvider())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备厂商与请求不一致");
        }
        return device;
    }
}