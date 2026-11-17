package com.threadfit.glucopilot.notification.mapper;

import com.threadfit.glucopilot.notification.entity.WorkReminderSource;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 仅统计服务器授权允许查看的队列，Service决定是否调用对应查询。 */
@Mapper
public interface AdminWorkReminderMapper {
    List<WorkReminderSource> sources(@Param("opsId") long opsId, @Param("kind") String kind);

    int saveReads(
            @Param("opsId") long opsId,
            @Param("kind") String kind,
            @Param("sources") List<WorkReminderSource> sources);
}
