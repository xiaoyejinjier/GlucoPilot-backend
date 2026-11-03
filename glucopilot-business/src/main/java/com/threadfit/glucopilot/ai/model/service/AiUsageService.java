package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.model.dto.AiUsageDtos;

import java.time.LocalDate;

/**
 * AI 用量报表（只读）。
 *
 * <p>数据来源是对话消息表里已经落库的 token 数，见 {@link AiUsageDtos} 的口径说明。
 */
public interface AiUsageService {

    /**
     * 取一段区间的用量。
     *
     * @param startDate 起始日期（含），为空则按 {@link #DEFAULT_DAYS} 天窗口推算
     * @param endDate   结束日期（含），为空则取今天
     */
    AiUsageDtos.Report report(LocalDate startDate, LocalDate endDate);

    /** 不传参数时默认看最近多少天。 */
    int DEFAULT_DAYS = 7;

    /** 单次查询允许的最大跨度（天）。再大就扫全表了，且前端表格也放不下。 */
    int MAX_RANGE_DAYS = 92;
}
