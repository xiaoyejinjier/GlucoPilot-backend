package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Maps GlucoPilot's existing sys_config table to RuoYi parameter semantics. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gp_app_config")
public class SysConfig extends BaseEntity {
    private String configKey;
    private String configValue;
    private Integer valueType;
    private String configGroup;
    private String remark;
    private String modifierName;
}
