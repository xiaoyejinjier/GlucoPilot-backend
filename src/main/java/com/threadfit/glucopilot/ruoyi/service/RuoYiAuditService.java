package com.threadfit.glucopilot.ruoyi.service;

import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.ruoyi.entity.SysLoginLog;
import com.threadfit.glucopilot.ruoyi.entity.SysOperLog;
import com.threadfit.glucopilot.ruoyi.mapper.SysLoginLogMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Writes the RuoYi login and operation audit trails without changing the unified API response contract. */
@Service
@RequiredArgsConstructor
public class RuoYiAuditService {

    private final SysLoginLogMapper sysLoginLogMapper;
    private final SysOperLogMapper sysOperLogMapper;
    private final OpsAccountMapper opsAccountMapper;

    public void recordLogin(String username, boolean success, String message) {
        SysLoginLog log = new SysLoginLog();
        log.setUsername(safe(username, 64));
        log.setLoginStatus(success ? 1 : 0);
        log.setLoginMessage(safe(message, 255));
        log.setClientIp(safe(RequestContext.getClientIp(), 45));
        log.setUserAgent(safe(RequestContext.getUserAgent(), 512));
        log.setLoginAt(LocalDateTime.now());
        sysLoginLogMapper.insert(log);
    }

    public void recordOperation(String title, String businessType, String detail) {
        Long opsId = RequestContext.getOpsId();
        SysOperLog log = new SysOperLog();
        log.setOpsId(opsId == null ? 0L : opsId);
        log.setTitle(safe(title, 64));
        log.setBusinessType(safe(businessType, 32));
        log.setOperatorName(operatorName(opsId));
        log.setClientIp(safe(RequestContext.getClientIp(), 45));
        log.setRequestParam(safe(detail, 2000));
        log.setOperStatus(1);
        log.setOperAt(LocalDateTime.now());
        sysOperLogMapper.insert(log);
    }

    public void recordOperationFailure(String title, String businessType, String detail, Throwable error) {
        Long opsId = RequestContext.getOpsId();
        SysOperLog log = new SysOperLog();
        log.setOpsId(opsId == null ? 0L : opsId);
        log.setTitle(safe(title, 64));
        log.setBusinessType(safe(businessType, 32));
        log.setOperatorName(operatorName(opsId));
        log.setClientIp(safe(RequestContext.getClientIp(), 45));
        log.setRequestParam(safe(detail, 2000));
        log.setOperStatus(0);
        log.setErrorMessage(safe(error == null ? null : error.getMessage(), 4000));
        log.setOperAt(LocalDateTime.now());
        sysOperLogMapper.insert(log);
    }

    private String operatorName(Long opsId) {
        if (opsId == null) {
            return "anonymous";
        }
        OpsAccount account = opsAccountMapper.selectById(opsId);
        return account == null ? "ops-" + opsId : account.getUsername();
    }

    private static String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
