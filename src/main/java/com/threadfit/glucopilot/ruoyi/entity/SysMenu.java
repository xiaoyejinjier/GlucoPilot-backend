package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {
    private String menuName;
    private Long parentId;
    private Integer sortNo;
    private String pathUrl;
    private String componentName;
    private String queryString;
    private Integer isFrame;
    private Integer isCache;
    private String menuType;
    private Integer visible;
    private Integer menuStatus;
    private String permCode;
    private String iconName;
    private String remark;
}
