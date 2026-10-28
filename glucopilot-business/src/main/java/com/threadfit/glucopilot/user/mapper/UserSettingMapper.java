package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.UserSetting;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSettingMapper extends BaseMapper<UserSetting> {

    /** 账号注销：物理删除偏好设置（纯用户侧数据，无留存理由）。 */
    @Delete("DELETE FROM user_setting WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
