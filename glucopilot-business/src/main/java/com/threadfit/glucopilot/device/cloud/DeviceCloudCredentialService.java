package com.threadfit.glucopilot.device.cloud;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.support.DeviceProviders;
import com.threadfit.glucopilot.device.entity.DeviceCloudCredential;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.DeviceCloudCredentialMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceCloudCredentialService {

    private static final String STATE_KEY_PREFIX = "device:cloud:oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final DeviceCloudCredentialMapper credentialMapper;
    private final UserDeviceMapper userDeviceMapper;
    private final TokenCrypto tokenCrypto;
    private final HuaweiOAuthClient huaweiOAuthClient;
    private final DeviceCloudProperties cloudProperties;
    private final StringRedisTemplate redisTemplate;

    public boolean isHuaweiCloudReady() {
        HuaweiCloudProperties h = cloudProperties.getHuawei();
        return h != null
                && h.isEnabled()
                && StringUtils.hasText(h.getClientId())
                && StringUtils.hasText(h.getClientSecret())
                && StringUtils.hasText(h.getRedirectUri())
                && tokenCrypto.isReady();
    }

    public void requireHuaweiCloudReady() {
        if (!isHuaweiCloudReady()) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "华为健康云未启用或未配置");
        }
    }

    /**
     * 签发授权 URL；state 存 Redis，绑定 userId 与可选 deviceId。
     */
    public AuthorizeSession beginHuaweiAuthorize(Long userId, Long deviceId) {
        requireHuaweiCloudReady();
        if (deviceId != null) {
            requireOwnedHuaweiDevice(userId, deviceId);
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        String payload = userId + "|" + (deviceId == null ? "" : deviceId);
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, payload, STATE_TTL);
        String url = huaweiOAuthClient.buildAuthorizeUrl(state);
        return new AuthorizeSession(url, state);
    }

    /**
     * 华为回调：校验 state、换票、加密落库。
     *
     * @return 回跳用的 userId（供日志）
     */
    @Transactional
    public Long completeHuaweiCallback(String code, String state) {
        requireHuaweiCloudReady();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(state)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "授权回调参数缺失");
        }
        String cached = redisTemplate.opsForValue().get(STATE_KEY_PREFIX + state);
        redisTemplate.delete(STATE_KEY_PREFIX + state);
        if (!StringUtils.hasText(cached)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "授权状态已失效，请重试");
        }
        String[] parts = cached.split("\\|", -1);
        Long userId = Long.valueOf(parts[0]);
        Long deviceId = parts.length > 1 && StringUtils.hasText(parts[1]) ? Long.valueOf(parts[1]) : null;

        HuaweiOAuthClient.TokenBundle token = huaweiOAuthClient.exchangeCode(code.trim());
        upsertCredential(userId, deviceId, DeviceProviders.HUAWEI, token);
        return userId;
    }

    @Transactional
    public void upsertCredential(Long userId, Long deviceId, String provider, HuaweiOAuthClient.TokenBundle token) {
        DeviceCloudCredential existing = credentialMapper.selectOne(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getUserId, userId)
                .eq(DeviceCloudCredential::getProvider, provider)
                .eq(DeviceCloudCredential::getVendorSubject, token.vendorSubject())
                .last("LIMIT 1 FOR UPDATE"));

        String accessEnc = tokenCrypto.encrypt(token.accessToken());
        String refreshEnc = StringUtils.hasText(token.refreshToken())
                ? tokenCrypto.encrypt(token.refreshToken())
                : null;

        if (existing == null) {
            DeviceCloudCredential row = new DeviceCloudCredential();
            row.setUserId(userId);
            row.setDeviceId(deviceId);
            row.setProvider(provider);
            row.setVendorSubject(token.vendorSubject());
            row.setAccessTokenEnc(accessEnc);
            row.setRefreshTokenEnc(refreshEnc);
            row.setTokenExpiresAt(token.expiresAt());
            row.setScopes(StringUtils.hasText(token.scopes()) ? token.scopes() : cloudProperties.getHuawei().getScopes());
            row.setStatus(1);
            credentialMapper.insert(row);
            return;
        }
        existing.setDeviceId(deviceId != null ? deviceId : existing.getDeviceId());
        existing.setAccessTokenEnc(accessEnc);
        if (refreshEnc != null) {
            existing.setRefreshTokenEnc(refreshEnc);
        }
        existing.setTokenExpiresAt(token.expiresAt());
        if (StringUtils.hasText(token.scopes())) {
            existing.setScopes(token.scopes());
        }
        existing.setStatus(1);
        credentialMapper.updateById(existing);
    }

    public DeviceCloudCredential requireActiveCredential(Long userId, Long deviceId) {
        DeviceCloudCredential row = findActiveByDevice(userId, deviceId);
        if (row == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "设备未授权华为健康云");
        }
        return row;
    }

    public DeviceCloudCredential findActiveByDevice(Long userId, Long deviceId) {
        return credentialMapper.selectOne(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getUserId, userId)
                .eq(DeviceCloudCredential::getDeviceId, deviceId)
                .eq(DeviceCloudCredential::getProvider, DeviceProviders.HUAWEI)
                .eq(DeviceCloudCredential::getStatus, 1)
                .last("LIMIT 1"));
    }

    public List<DeviceCloudCredential> listActiveHuawei(Long userId) {
        return credentialMapper.selectList(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getUserId, userId)
                .eq(DeviceCloudCredential::getProvider, DeviceProviders.HUAWEI)
                .eq(DeviceCloudCredential::getStatus, 1)
                .isNotNull(DeviceCloudCredential::getDeviceId));
    }

    public Set<Long> linkedDeviceIds(Long userId, List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Set.of();
        }
        List<DeviceCloudCredential> rows = credentialMapper.selectList(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getUserId, userId)
                .eq(DeviceCloudCredential::getProvider, DeviceProviders.HUAWEI)
                .eq(DeviceCloudCredential::getStatus, 1)
                .in(DeviceCloudCredential::getDeviceId, deviceIds));
        Set<Long> ids = new HashSet<>();
        for (DeviceCloudCredential row : rows) {
            if (row.getDeviceId() != null) {
                ids.add(row.getDeviceId());
            }
        }
        return ids;
    }

    /** 管理端：批量查有效云授权设备 ID（不限用户）。 */
    public Set<Long> linkedDeviceIds(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Set.of();
        }
        List<DeviceCloudCredential> rows = credentialMapper.selectList(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getStatus, 1)
                .isNotNull(DeviceCloudCredential::getDeviceId)
                .in(DeviceCloudCredential::getDeviceId, deviceIds));
        Set<Long> ids = new HashSet<>();
        for (DeviceCloudCredential row : rows) {
            if (row.getDeviceId() != null) {
                ids.add(row.getDeviceId());
            }
        }
        return ids;
    }

    /** 管理端：全部有效云授权的 device_id（用于 cloudLinked 筛选）。 */
    public Set<Long> allLinkedDeviceIds() {
        List<DeviceCloudCredential> rows = credentialMapper.selectList(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getStatus, 1)
                .isNotNull(DeviceCloudCredential::getDeviceId)
                .select(DeviceCloudCredential::getDeviceId));
        Set<Long> ids = new HashSet<>();
        for (DeviceCloudCredential row : rows) {
            if (row.getDeviceId() != null) {
                ids.add(row.getDeviceId());
            }
        }
        return ids;
    }

    /** 管理端：按设备查任意有效云凭证（不校验归属）。 */
    public DeviceCloudCredential findActiveByDeviceId(Long deviceId) {
        return credentialMapper.selectOne(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getDeviceId, deviceId)
                .eq(DeviceCloudCredential::getStatus, 1)
                .orderByDesc(DeviceCloudCredential::getId)
                .last("LIMIT 1"));
    }

    @Transactional
    public void revokeByDevice(Long userId, Long deviceId) {
        requireOwnedDevice(userId, deviceId);
        revokeActiveRows(credentialMapper.selectList(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getUserId, userId)
                .eq(DeviceCloudCredential::getDeviceId, deviceId)
                .eq(DeviceCloudCredential::getStatus, 1)));
    }

    /** 管理端：按设备撤销全部有效云授权，不校验 C 端归属。 */
    @Transactional
    public void revokeByDeviceId(Long deviceId) {
        revokeActiveRows(credentialMapper.selectList(new LambdaQueryWrapper<DeviceCloudCredential>()
                .eq(DeviceCloudCredential::getDeviceId, deviceId)
                .eq(DeviceCloudCredential::getStatus, 1)));
    }

    private void revokeActiveRows(List<DeviceCloudCredential> rows) {
        for (DeviceCloudCredential row : rows) {
            row.setStatus(0);
            row.setAccessTokenEnc(tokenCrypto.isReady() ? tokenCrypto.encrypt("revoked") : "revoked");
            row.setRefreshTokenEnc(null);
            row.setTokenExpiresAt(null);
            credentialMapper.updateById(row);
        }
    }

    /**
     * 若 access 将过期则 refresh 并回写；返回可用 access_token 明文。
     */
    @Transactional
    public String resolveAccessToken(DeviceCloudCredential credential) {
        LocalDateTime expiresAt = credential.getTokenExpiresAt();
        boolean nearExpiry = expiresAt == null || !expiresAt.isAfter(LocalDateTime.now().plusMinutes(2));
        if (!nearExpiry) {
            return tokenCrypto.decrypt(credential.getAccessTokenEnc());
        }
        if (!StringUtils.hasText(credential.getRefreshTokenEnc())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "华为授权已过期，请重新授权");
        }
        String refreshPlain = tokenCrypto.decrypt(credential.getRefreshTokenEnc());
        HuaweiOAuthClient.TokenBundle refreshed = huaweiOAuthClient.refresh(refreshPlain);
        credential.setAccessTokenEnc(tokenCrypto.encrypt(refreshed.accessToken()));
        if (StringUtils.hasText(refreshed.refreshToken())) {
            credential.setRefreshTokenEnc(tokenCrypto.encrypt(refreshed.refreshToken()));
        }
        credential.setTokenExpiresAt(refreshed.expiresAt());
        if (StringUtils.hasText(refreshed.scopes())) {
            credential.setScopes(refreshed.scopes());
        }
        credentialMapper.updateById(credential);
        return refreshed.accessToken();
    }

    public UserDevice requireOwnedHuaweiDevice(Long userId, Long deviceId) {
        UserDevice device = requireOwnedDevice(userId, deviceId);
        String provider = DeviceProviders.normalize(device.getProvider());
        if (!DeviceProviders.HUAWEI.equals(provider)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "仅华为设备支持云授权");
        }
        return device;
    }

    public UserDevice requireOwnedDevice(Long userId, Long deviceId) {
        UserDevice device = userDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        if (device.getUserId() == null || !device.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "只能操作本人设备");
        }
        return device;
    }

    public record AuthorizeSession(String authorizeUrl, String state) {
    }
}
