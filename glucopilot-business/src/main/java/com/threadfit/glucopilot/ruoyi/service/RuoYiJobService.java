package com.threadfit.glucopilot.ruoyi.service;

import com.ruoyi.quartz.service.ISysJobLogService;
import com.ruoyi.quartz.service.ISysJobService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.SysJob;
import com.threadfit.glucopilot.ruoyi.entity.SysJobLog;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuoYiJobService {
  private static final Map<String, String> TARGETS =
      Map.of(
          "system.cleanup-sessions",
          "gpTasks.cleanupSessions()",
          "system.refresh-permissions",
          "gpTasks.refreshPermissions()",
          "system.health-check",
          "gpTasks.healthCheck()");
  private final ISysJobService jobs;
  private final ISysJobLogService logs;

  private static LocalDateTime local(Date d) {
    return d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneId.of("Asia/Shanghai"));
  }

  public List<SysJob> list() {
    return jobs.selectJobList(new com.ruoyi.quartz.domain.SysJob()).stream()
        .map(this::view)
        .toList();
  }

  public List<SysJobLog> listLogs(Long jobId) {
    var q = new com.ruoyi.quartz.domain.SysJobLog();
    if (jobId != null) {
      var job = required(jobId);
      q.setJobName(job.getJobName());
      q.setJobGroup(job.getJobGroup());
    }
    return logs.selectJobLogList(q).stream()
        .limit(500)
        .map(
            v -> {
              var i = new SysJobLog();
              i.setId(v.getJobLogId());
              i.setJobId(jobId);
              i.setJobName(v.getJobName());
              i.setJobGroup(v.getJobGroup());
              i.setInvokeTarget(external(v.getInvokeTarget()));
              i.setJobMessage(v.getJobMessage());
              i.setJobStatus("0".equals(v.getStatus()) ? 1 : 0);
              i.setExceptionInfo(v.getExceptionInfo());
              i.setStartAt(local(v.getStartTime()));
              i.setEndAt(local(v.getEndTime()));
              return i;
            })
        .toList();
  }

  private String external(String target) {
    return TARGETS.entrySet().stream()
        .filter(e -> e.getValue().equals(target))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(target);
  }

  private SysJob view(com.ruoyi.quartz.domain.SysJob v) {
    var i = new SysJob();
    i.setId(v.getJobId());
    i.setJobName(v.getJobName());
    i.setJobGroup(v.getJobGroup());
    i.setInvokeTarget(external(v.getInvokeTarget()));
    i.setCronExpression(v.getCronExpression());
    i.setMisfirePolicy(
        switch (v.getMisfirePolicy()) {
          case "2" -> "FIRE_AND_PROCEED";
          case "1" -> "IGNORE_MISFIRES";
          default -> "DO_NOTHING";
        });
    i.setConcurrentAllowed("0".equals(v.getConcurrent()) ? 1 : 0);
    i.setJobStatus("0".equals(v.getStatus()) ? 1 : 0);
    i.setRemark(v.getRemark());
    return i;
  }

  private com.ruoyi.quartz.domain.SysJob input(Long id, RuoYiAdminDtos.JobRequest r) {
    if (!TARGETS.containsKey(r.getInvokeTarget())
        || !jobs.checkCronExpressionIsValid(r.getCronExpression()))
      throw new BizException(ErrorCode.BAD_REQUEST, "任务目标或 Cron 不合法");
    var v = new com.ruoyi.quartz.domain.SysJob();
    v.setJobId(id);
    v.setJobName(r.getJobName());
    v.setJobGroup(r.getJobGroup());
    v.setInvokeTarget(TARGETS.get(r.getInvokeTarget()));
    v.setCronExpression(r.getCronExpression());
    v.setMisfirePolicy(
        switch (r.getMisfirePolicy()) {
          case "FIRE_AND_PROCEED" -> "2";
          case "IGNORE_MISFIRES" -> "1";
          default -> "3";
        });
    v.setConcurrent(r.getConcurrentAllowed() == 1 ? "0" : "1");
    v.setStatus(r.getStatus() == 1 ? "0" : "1");
    v.setRemark(r.getRemark());
    return v;
  }

  public SysJob create(RuoYiAdminDtos.JobRequest r) {
    try {
      var v = input(null, r);
      jobs.insertJob(v);
      if (r.getStatus() == 1) jobs.resumeJob(v);
      return view(required(v.getJobId()));
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public SysJob update(Long id, RuoYiAdminDtos.JobRequest r) {
    required(id);
    try {
      var v = input(id, r);
      jobs.updateJob(v);
      jobs.changeStatus(v);
      return view(required(id));
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public void delete(Long id) {
    try {
      jobs.deleteJob(required(id));
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public void runNow(Long id) {
    try {
      var job = required(id);
      if (!TARGETS.containsValue(job.getInvokeTarget()))
        throw new BizException(ErrorCode.BAD_REQUEST, "任务不在白名单");
      jobs.run(job);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  private com.ruoyi.quartz.domain.SysJob required(Long id) {
    var v = jobs.selectJobById(id);
    if (v == null) throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
    return v;
  }

  private RuntimeException failure(Exception e) {
    return e instanceof RuntimeException r ? r : new BizException(ErrorCode.BIZ_REJECTED, "任务调度失败");
  }
}
