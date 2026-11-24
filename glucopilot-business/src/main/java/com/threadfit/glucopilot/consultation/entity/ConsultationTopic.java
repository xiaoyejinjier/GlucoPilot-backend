package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

/** 求助分类字典，广场筛选与发布共用。 */
@Data
public class ConsultationTopic {
    private String code;
    private String name;
    private String groupName;
    private int sortNo;
}
