package com.threadfit.glucopilot.localization.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 翻译缓存的审计行（管理端「AI 审计 → 翻译」）。
 *
 * <p>与 {@link LocalizationCacheRow} 的区别：那个是给业务读缓存用的（只要 status/translated_json），
 * 这个要把 {@code kind/resource_id/source_hash/prompt_ver} 一起吐出来——审计要回答的是
 * 「这条译文是哪版提示词、针对哪版原文生成的」。
 */
@Data
public class LocalizationAuditRow {
    private String kind;
    private String resourceId;
    private String sourceHash;
    private String locale;
    private String status;
    private String promptVer;
    private String translatedJson;
    private LocalDateTime updatedAt;
}
