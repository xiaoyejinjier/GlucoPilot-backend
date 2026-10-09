package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gp_legacy_sys_role")
public class SysRole extends BaseEntity {

    private String roleCode;
    private String roleName;
    private Integer sortNo;
    private Integer roleStatus;
    private String remark;
}
