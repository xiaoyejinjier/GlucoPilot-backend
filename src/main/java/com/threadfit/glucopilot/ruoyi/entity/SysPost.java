package com.threadfit.glucopilot.ruoyi.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_post")
public class SysPost extends BaseEntity {
    private String postCode;
    private String postName;
    private Integer postSort;
    private Integer postStatus;
    private String remark;
}
