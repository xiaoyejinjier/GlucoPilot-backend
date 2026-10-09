package com.threadfit.glucopilot.ruoyi.job;

import com.threadfit.glucopilot.auth.service.OpsSessionRegistry;
import com.threadfit.glucopilot.ruoyi.service.RuoYiSystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Fixed task targets, invoked by the official Quartz module. */
@Component("gpTasks")
@RequiredArgsConstructor
public class GlucoPilotTasks {
  private final OpsSessionRegistry sessions;
  private final RuoYiSystemService system;

  public void cleanupSessions() {
    sessions.online();
  }

  public void refreshPermissions() {
    sessions.revokeAll();
  }

  public void healthCheck() {
    if (!system.cacheStatus().isRedisReachable())
      throw new IllegalStateException("Redis unavailable");
  }
}
