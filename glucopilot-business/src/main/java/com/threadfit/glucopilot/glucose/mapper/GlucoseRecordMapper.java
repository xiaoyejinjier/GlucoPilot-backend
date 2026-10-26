package com.threadfit.glucopilot.glucose.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GlucoseRecordMapper extends BaseMapper<GlucoseRecord> {

    /**
     * 账号注销：物理删除该用户的全部血糖记录。
     * <p>
     * 刻意绕开 {@code @TableLogic}：注销要求"删除"，逻辑删除只是把行藏起来，
     * 健康数据仍然留在库里，与 PIPL 第 47 条不符。幂等：无记录时返回 0。
     */
    @Delete("DELETE FROM glucose_record WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
