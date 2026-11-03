package com.threadfit.glucopilot.localization.entity;

import lombok.Data;

/** 文章原文查询投影；预览和正文仅查询各自需要的字段。 */
@Data
public class LocalizationSourceRow {
    private Long id;
    private String title;
    private String summaryText;
    private String contentBody;
}
