package com.threadfit.glucopilot.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.device.entity.UserDevice;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface UserDeviceMapper extends BaseMapper<UserDevice> {

    /** 账号注销：物理删除绑定关系（设备本体档案归平台，不随账号删）。 */
    @Delete("DELETE FROM user_device WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
