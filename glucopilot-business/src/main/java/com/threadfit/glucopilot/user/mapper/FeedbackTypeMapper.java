package com.threadfit.glucopilot.user.mapper;

import com.threadfit.glucopilot.user.dto.FeedbackDtos;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FeedbackTypeMapper {
    List<FeedbackDtos.TypeItem> selectTypes(@Param("enabledOnly") boolean enabledOnly);
}
