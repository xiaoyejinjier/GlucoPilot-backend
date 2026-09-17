package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.IdUtils;
import com.threadfit.glucopilot.auth.dto.OpsAuthDtos;
import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.entity.OpsProfile;
import com.threadfit.glucopilot.auth.entity.OpsSession;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.auth.mapper.OpsProfileMapper;
import com.threadfit.glucopilot.auth.mapper.OpsSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OpsAuthService {

    public static final long TOKEN_SECONDS = 8L * 3600;

    private final OpsAccountMapper opsAccountMapper;
    private final OpsProfileMapper opsProfileMapper;
    private final OpsSessionMapper opsSessionMapper;
    private final OpsRoleResolver opsRoleResolver;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public OpsAuthDtos.SessionResponse login(OpsAuthDtos.LoginRequest request) {
        OpsAccount account = opsAccountMapper.selectOne(new LambdaQueryWrapper<OpsAccount>()
                .eq(OpsAccount::getUsername, request.getUsername().trim())
                .last("LIMIT 1"));
        if (account == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "账号或密码错误");
        }
        if (account.getAccountStatus() == null
                || account.getAccountStatus() != CodeEnum.OpsAccountStatus.NORMAL.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        String hash = account.getPasswordHash();
        if (!StringUtils.hasText(hash) || !passwordEncoder.matches(request.getPassword(), hash)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "账号或密码错误");
        }

        account.setLastLoginAt(LocalDateTime.now());
        account.setLastLoginIp(RequestContext.getClientIp());
        opsAccountMapper.updateById(account);

        OpsSession session = new OpsSession();
        session.setOpsId(account.getId());
        session.setAccessToken(IdUtils.uuid());
        session.setExpireAt(LocalDateTime.now().plusSeconds(TOKEN_SECONDS));
        session.setClientIp(RequestContext.getClientIp());
        session.setUserAgent(truncateUa(RequestContext.getUserAgent()));
        session.setSessionStatus(1);
        opsSessionMapper.insert(session);

        OpsProfile profile = opsProfileMapper.selectOne(new LambdaQueryWrapper<OpsProfile>()
                .eq(OpsProfile::getOpsId, account.getId())
                .last("LIMIT 1"));
        String nickname = profile != null && StringUtils.hasText(profile.getRealName())
                ? profile.getRealName()
                : account.getUsername();

        return OpsAuthDtos.SessionResponse.builder()
                .accessToken(session.getAccessToken())
                .expiresIn(TOKEN_SECONDS)
                .username(account.getUsername())
                .nickname(nickname)
                .role(opsRoleResolver.resolveFrontRole(account.getId()))
                .opsId(account.getId())
                .build();
    }

    @Transactional
    public void logout(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return;
        }
        OpsSession session = opsSessionMapper.selectOne(new LambdaQueryWrapper<OpsSession>()
                .eq(OpsSession::getAccessToken, accessToken)
                .last("LIMIT 1"));
        if (session != null && session.getSessionStatus() != null && session.getSessionStatus() == 1) {
            session.setSessionStatus(0);
            opsSessionMapper.updateById(session);
        }
    }

    public OpsSession requireValidSession(String accessToken) {
        OpsSession session = opsSessionMapper.selectOne(new LambdaQueryWrapper<OpsSession>()
                .eq(OpsSession::getAccessToken, accessToken)
                .eq(OpsSession::getSessionStatus, 1)
                .last("LIMIT 1"));
        if (session == null || session.getExpireAt() == null || session.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        OpsAccount account = opsAccountMapper.selectById(session.getOpsId());
        if (account == null
                || account.getAccountStatus() == null
                || account.getAccountStatus() != CodeEnum.OpsAccountStatus.NORMAL.getCode()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号不可用");
        }
        return session;
    }

    private static String truncateUa(String ua) {
        if (ua == null) {
            return null;
        }
        return ua.length() <= 512 ? ua : ua.substring(0, 512);
    }
}
