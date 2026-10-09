package com.threadfit.glucopilot.ruoyi.service;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.SysLogininfor;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.service.ISysLogininforService;
import com.ruoyi.system.service.ISysOperLogService;
import com.threadfit.glucopilot.common.context.RequestContext;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuoYiAuditService {
  private final ISysOperLogService operations;
  private final ISysLogininforService logins;

  public void recordLogin(String name, boolean success, String message) {
    var log = new SysLogininfor();
    log.setUserName(safe(name, 50));
    log.setStatus(success ? "0" : "1");
    log.setMsg(safe(message, 255));
    log.setIpaddr(RequestContext.getClientIp());
    log.setLoginTime(new Date());
    logins.insertLogininfor(log);
  }

  public void recordOperation(String title, String type, String detail) {
    record(title, type, detail, null);
  }

  public void recordOperationFailure(String title, String type, String detail, Throwable error) {
    record(title, type, detail, error);
  }

  private void record(String title, String type, String detail, Throwable error) {
    var log = new SysOperLog();
    log.setTitle(safe(title, 50));
    log.setBusinessType(
        switch (type) {
          case "INSERT", "INSERT_TYPE", "INSERT_DATA" -> 1;
          case "UPDATE", "UPDATE_TYPE", "UPDATE_DATA" -> 2;
          case "DELETE", "DELETE_TYPE", "DELETE_DATA" -> 3;
          default -> 0;
        });
    log.setOperName(SecurityUtils.getUsername());
    log.setOperIp(RequestContext.getClientIp());
    log.setOperParam(safe(detail, 2000));
    log.setStatus(error == null ? 0 : 1);
    log.setErrorMsg(safe(error == null ? null : error.getMessage(), 2000));
    log.setOperTime(new Date());
    log.setCostTime(0L);
    operations.insertOperlog(log);
  }

  private static String safe(String s, int max) {
    return s == null ? "" : s.substring(0, Math.min(s.length(), max));
  }
}
