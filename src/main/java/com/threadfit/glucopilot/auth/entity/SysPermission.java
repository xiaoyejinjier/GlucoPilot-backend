package com.threadfit.glucopilot.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * RuoYi 风格的权限资源：菜单、按钮和接口共用一套权限标识。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity {

    private String permCode;
    private String permName;
    /** 1 菜单、2 按钮、3 接口。 */
    private Integer permType;
    private Long parentId;
    private String pathUrl;
    private Integer sortNo;
    private Integer permStatus;
}
