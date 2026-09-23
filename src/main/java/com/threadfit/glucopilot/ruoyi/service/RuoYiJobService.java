package com.threadfit.glucopilot.ruoyi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.SysJob;
import com.threadfit.glucopilot.ruoyi.entity.SysJobLog;
import com.threadfit.glucopilot.ruoyi.mapper.SysJobLogMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/** RuoYi-compatible persisted jobs using an explicit safe-target allowlist. */
@Service
@RequiredArgsConstructor
public class RuoYiJobService {

    private static final Set<String> ALLOWED_TARGETS = Set.of(
            "system.cleanup-sessions", "system.refresh-permissions", "system.health-check");

    private final SysJobMapper sysJobMapper;
    private final SysJobLogMapper sysJobLogMapper;
    private final ThreadPoolTaskScheduler ruoyiTaskScheduler;
    private final RuoYiSystemService ruoyiSystemService;
    private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    @EventListener(ApplicationReadyEvent.class)
    public void restoreActiveJobs() {
        sysJobMapper.selectList(new LambdaQueryWrapper<SysJob>().eq(SysJob::getJobStatus, 1))
                .forEach(this::schedule);
    }

    public List<SysJob> list() {
        return sysJobMapper.selectList(new LambdaQueryWrapper<SysJob>().orderByAsc(SysJob::getJobGroup).orderByAsc(SysJob::getJobName));
    }

    public List<SysJobLog> listLogs(Long jobId) {
        LambdaQueryWrapper<SysJobLog> query = new LambdaQueryWrapper<SysJobLog>().orderByDesc(SysJobLog::getStartAt).last("LIMIT 500");
        if (jobId != null) query.eq(SysJobLog::getJobId, jobId);
        return sysJobLogMapper.selectList(query);
    }

    @Transactional
    public SysJob create(RuoYiAdminDtos.JobRequest request) {
        validate(request, null);
        SysJob job = new SysJob();
        apply(job, request);
        sysJobMapper.insert(job);
        schedule(job);
        return job;
    }

    @Transactional
    public SysJob update(Long id, RuoYiAdminDtos.JobRequest request) {
        SysJob job = required(id);
        validate(request, id);
        cancel(id);
        apply(job, request);
        sysJobMapper.updateById(job);
        schedule(job);
        return job;
    }

    @Transactional
    public void delete(Long id) {
        SysJob job = required(id);
        cancel(id);
        job.setIsDeleted(1);
        job.setDeletedAt(LocalDateTime.now());
        job.setDeletedBy(com.threadfit.glucopilot.common.context.RequestContext.operatorIdOrZero());
        sysJobMapper.updateById(job);
    }

    public void runNow(Long id) {
        execute(required(id));
    }

    private void schedule(SysJob job) {
        if (job.getJobStatus() == null || job.getJobStatus() != 1 || job.getId() == null) return;
        cancel(job.getId());
        scheduled.put(job.getId(), ruoyiTaskScheduler.schedule(() -> executeById(job.getId()), new CronTrigger(job.getCronExpression())));
    }

    private void executeById(Long id) {
        SysJob job = sysJobMapper.selectById(id);
        if (job != null && job.getJobStatus() != null && job.getJobStatus() == 1) execute(job);
    }

    private void execute(SysJob job) {
        if (job.getConcurrentAllowed() != null && job.getConcurrentAllowed() == 0 && !running.add(job.getId())) {
            return;
        }
        SysJobLog log = new SysJobLog();
        log.setJobId(job.getId()); log.setJobName(job.getJobName()); log.setJobGroup(job.getJobGroup()); log.setInvokeTarget(job.getInvokeTarget());
        log.setStartAt(LocalDateTime.now()); log.setJobStatus(1);
        try {
            String message = dispatch(job.getInvokeTarget());
            log.setJobMessage(message);
        } catch (Exception ex) {
            log.setJobStatus(0);
            log.setExceptionInfo(ex.getMessage());
            log.setJobMessage("任务执行失败");
        } finally {
            log.setEndAt(LocalDateTime.now());
            sysJobLogMapper.insert(log);
            running.remove(job.getId());
        }
    }

    private String dispatch(String target) {
        return switch (target) {
            case "system.cleanup-sessions" -> "会话由在线监控按过期时间过滤；无需清理共享数据源";
            case "system.refresh-permissions" -> "权限实时从数据库读取，已完成刷新";
            case "system.health-check" -> ruoyiSystemService.cacheStatus().isRedisReachable() ? "健康检查通过" : "健康检查完成：Redis 暂不可达";
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不允许执行的任务目标");
        };
    }

    private void validate(RuoYiAdminDtos.JobRequest request, Long self) {
        if (!ALLOWED_TARGETS.contains(request.getInvokeTarget().trim())) throw new BizException(ErrorCode.BAD_REQUEST, "任务目标必须来自平台白名单");
        try { CronExpression.parse(request.getCronExpression().trim()); } catch (IllegalArgumentException ex) { throw new BizException(ErrorCode.BAD_REQUEST, "Cron 表达式不合法"); }
        String name = request.getJobName().trim(); String group = request.getJobGroup() == null ? "DEFAULT" : request.getJobGroup().trim();
        SysJob existing = sysJobMapper.selectOne(new LambdaQueryWrapper<SysJob>().eq(SysJob::getJobName, name).eq(SysJob::getJobGroup, group).last("LIMIT 1"));
        if (existing != null && !existing.getId().equals(self)) throw new BizException(ErrorCode.CONFLICT, "同一分组的任务名称已存在");
    }

    private void apply(SysJob job, RuoYiAdminDtos.JobRequest r) {
        job.setJobName(r.getJobName().trim()); job.setJobGroup(r.getJobGroup() == null ? "DEFAULT" : r.getJobGroup().trim());
        job.setInvokeTarget(r.getInvokeTarget().trim()); job.setCronExpression(r.getCronExpression().trim()); job.setMisfirePolicy(r.getMisfirePolicy() == null ? "DO_NOTHING" : r.getMisfirePolicy().trim());
        job.setConcurrentAllowed(r.getConcurrentAllowed()); job.setJobStatus(r.getStatus()); job.setRemark(r.getRemark() == null ? null : r.getRemark().trim());
    }

    private void cancel(Long id) { ScheduledFuture<?> future = scheduled.remove(id); if (future != null) future.cancel(false); }
    private SysJob required(Long id) { SysJob job = sysJobMapper.selectById(id); if (job == null) throw new BizException(ErrorCode.NOT_FOUND, "定时任务不存在"); return job; }
}
