package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 账号注销：脱敏保留资料行（<b>不删行、不置 is_deleted</b>）。
     * <p>
     * 社区文章/评论仍指向这个用户，作者链路要能解析出"已注销用户"；
     * 把行删掉或置 is_deleted=1，作者解析会退化成"用户不存在"，公开内容跟着消失。
     */
    @Update("UPDATE user_profile SET nickname = '已注销用户', avatar_url = NULL, avatar_text = NULL, "
            + "bio = '', gender = 0 WHERE user_id = #{userId}")
    int anonymizeByUserId(@Param("userId") Long userId);
}
