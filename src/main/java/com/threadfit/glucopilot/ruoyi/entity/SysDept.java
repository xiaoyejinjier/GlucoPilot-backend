package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseEntity {
    private Long parentId;
    private String ancestors;
    private String deptName;
    private Integer sortNo;
    private String leader;
    private String phone;
    private String email;
    private Integer deptStatus;
    private String remark;
}
