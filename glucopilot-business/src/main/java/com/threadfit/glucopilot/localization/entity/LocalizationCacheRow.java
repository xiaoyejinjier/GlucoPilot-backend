package com.threadfit.glucopilot.localization.entity;

import lombok.Data;

/** 翻译缓存当前状态及已保存JSON，不把租约等内部字段返回前端。 */
@Data
public class LocalizationCacheRow {
    private String status;
    private String translatedJson;
}
