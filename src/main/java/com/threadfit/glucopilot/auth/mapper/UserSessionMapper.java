package com.threadfit.glucopilot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {
}
