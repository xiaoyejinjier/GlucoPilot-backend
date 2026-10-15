package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GlucoseAlertService {
    private final GlucoseAlertRepository repository;
    private static final Set<String> STATUSES=Set.of("PENDING","PROCESSING","RESOLVED","CLOSED");

    @Transactional(readOnly=true)
    public GlucoseAlertDtos.Result page(LocalDate start,LocalDate end,String keyword,String type,String status,PageQuery page) {
        LocalDate to=end==null?LocalDate.now():end;
        LocalDate from=start==null?to.minusDays(29):start;
        if(from.isAfter(to) || ChronoUnit.DAYS.between(from,to)>=92) bad("日期范围须按先后顺序且最多92天");
        if(keyword!=null && keyword.length()>64) bad("关键词最多64字");
        if(type!=null && !Set.of("LOW","HIGH").contains(type)) bad("异常类型不合法");
        if(status!=null && !STATUSES.contains(status)) bad("处理状态不合法");
        var filter=new GlucoseAlertRepository.Filter(from,to,keyword,type,status);
        var overview=repository.overview(filter);
        return new GlucoseAlertDtos.Result(PageResult.of(page.getPageNum(),page.getPageSize(),overview.get("total"),
                repository.page(filter,page)),overview,GlucoseAlertPolicy.LOW,GlucoseAlertPolicy.HIGH);
    }

    @Transactional(readOnly=true)
    public GlucoseAlertDtos.Detail detail(long id) {
        return new GlucoseAlertDtos.Detail(require(id),repository.history(id));
    }

    @Transactional
    public GlucoseAlertDtos.Event handle(long opsId,long id,GlucoseAlertDtos.Handling request) {
        if(!repository.lockSource(id)) throw new BizException(ErrorCode.NOT_FOUND,"记录不存在或已删除");
        var event=require(id);
        if(request.glucoseValue()==null || request.recordedAt()==null || request.expectedVersion()==null
            || request.expectedVersion()<0 || !Set.of("PROCESSING","RESOLVED","CLOSED").contains(request.status()==null?"":request.status()))
            bad("请提供有效的血糖快照、版本和处理状态");
        String note=request.note()==null?"":request.note().trim();
        if(note.isBlank() || note.length()>1000) bad("请填写1～1000字处理备注");
        if(request.userReply()!=null && request.userReply().length()>1000) bad("给用户的回复最多1000字");
        String userReply=request.userReply()==null?event.userReply():request.userReply().trim();
        if(userReply!=null && userReply.isBlank()) userReply=null;
        if(event.glucoseValue().compareTo(request.glucoseValue())!=0 || !event.recordedAt().equals(request.recordedAt()))
            conflict("血糖记录已更新，请刷新后处理最新数据");
        // 重复提交同一动作不增加版本或审计记录。
        if(event.status().equals(request.status()) && note.equals(event.note()) && Objects.equals(userReply,event.userReply())
                && String.valueOf(opsId).equals(event.handledBy())) return event;
        if(event.version()!=request.expectedVersion()) conflict("事件已被其他管理员处理，请刷新后重试");
        if(!Set.of("PENDING","PROCESSING").contains(event.status())
                && (!event.status().equals(request.status()) || !note.equals(event.note())))
            conflict("事件已结束，仅可补充或修改给用户的回复，不可更改状态和内部备注");
        LocalDateTime now=LocalDateTime.now();
        LocalDateTime repliedAt=Objects.equals(userReply,event.userReply())?event.repliedAt():userReply==null?null:now;
        if(repository.save(event,opsId,request.status(),note,userReply,repliedAt,now)!=1) conflict("事件状态已变化，请刷新后重试");
        String requestId=RequestContext.getRequestId();
        if(requestId!=null && requestId.length()>64) requestId=requestId.substring(0,64);
        repository.appendLog(event,opsId,request.status(),note,userReply,now,requestId);
        return require(id);
    }

    @Transactional(readOnly=true)
    public PageResult<GlucoseAlertDtos.UserReply> userReplies(Long userId,PageQuery page) {
        if(userId==null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return repository.userReplies(userId,page);
    }

    private GlucoseAlertDtos.Event require(long id) {
        var event=repository.find(id);
        if(event==null) throw new BizException(ErrorCode.NOT_FOUND,"当前异常已不存在，记录可能已更正或删除，请刷新列表");
        return event;
    }
    private static void bad(String message) { throw new BizException(ErrorCode.BAD_REQUEST,message); }
    private static void conflict(String message) { throw new BizException(ErrorCode.CONFLICT,message); }
}
