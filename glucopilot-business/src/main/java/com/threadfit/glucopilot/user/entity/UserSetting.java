package com.threadfit.glucopilot.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_setting")
public class UserSetting extends BaseEntity {

    private Long userId;
    private Integer fontSize;
    private Integer themeCode;
    private Integer notifyGlucose;
    private Integer notifyMeal;
    private Integer notifyNews;
    private Integer disclaimerAgreed;
    private String disclaimerVersion;
    private LocalDateTime disclaimerAgreedAt;
}
