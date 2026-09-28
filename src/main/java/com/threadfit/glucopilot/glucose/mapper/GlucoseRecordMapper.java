package com.threadfit.glucopilot.glucose.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GlucoseRecordMapper extends BaseMapper<GlucoseRecord> {
}
