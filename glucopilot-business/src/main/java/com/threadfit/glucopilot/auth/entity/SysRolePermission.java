package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色与 RuoYi 风格权限标识的关联。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gp_legacy_sys_role_permission")
public class SysRolePermission extends BaseEntity {

    private Long roleId;
    private Long permId;
}
