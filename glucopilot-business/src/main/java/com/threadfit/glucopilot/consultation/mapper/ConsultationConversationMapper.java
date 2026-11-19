package com.threadfit.glucopilot.consultation.mapper;

import com.threadfit.glucopilot.consultation.entity.ConsultationConversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConsultationConversationMapper {
    long count(@Param("user") long user);

    List<ConsultationConversation> list(
            @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

    long countIncoming(
            @Param("user") long user, @Param("id") long id, @Param("through") long through);

    int read(@Param("user") long user, @Param("id") long id, @Param("through") long through);
}
