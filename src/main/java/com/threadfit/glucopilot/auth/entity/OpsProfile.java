package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_profile")
public class OpsProfile extends BaseEntity {

    private Long opsId;
    /** RuoYi 部门；运营账号仍以 ops_account 为唯一登录主体。 */
    private Long deptId;
    private String realName;
    private String mobile;
    private String email;
}
