package com.threadfit.glucopilot.glucose.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 九时段节点字典（dict_glucose_node）。is_meal=1 为餐点，血糖写入须用非餐点。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dict_glucose_node")
public class GlucoseNode extends BaseEntity {

    private String nodeCode;
    private String nodeName;
    private String shortName;
    private Integer isMeal;
    private Integer sortNo;
    private Integer isEnabled;
}
