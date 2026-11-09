package com.threadfit.glucopilot.community.message.mapper;

import com.threadfit.glucopilot.community.message.entity.AdminMessageRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL位于resources/com/threadfit/glucopilot/community/message/mapper/AdminMessageMapper.xml。 */
@Mapper
public interface AdminMessageMapper {

    long count(@Param("q") AdminMessageQuery query);

    List<AdminMessageRow> page(
            @Param("q") AdminMessageQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
