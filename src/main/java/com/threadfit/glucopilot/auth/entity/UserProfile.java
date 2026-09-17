package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_profile")
public class UserProfile extends BaseEntity {

    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String avatarText;
    private Integer gender;
}
