package com.threadfit.glucopilot.ai.model.service.impl;

import com.threadfit.glucopilot.ai.model.dto.AiUsageDtos;
import com.threadfit.glucopilot.ai.model.mapper.AiUsageMapper;
import com.threadfit.glucopilot.ai.model.mapper.ChatUsageRow;
import com.threadfit.glucopilot.ai.model.service.AiUsageService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用量报表实现：把 SQL 的「天 × 模型」投影转成接口契约，并做区间校验。
 *
 * <h2>为什么把区间校验放在这里而不是 Controller</h2>
 * 区间规则（默认 7 天、上限 92 天、起止顺序）是<b>报表语义</b>，将来若从别处调用（定时汇总、
 * 导出）也应遵守同一套；放 Controller 会让「换个入口就绕过校验」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageServiceImpl implements AiUsageService {

    private final AiUsageMapper usageMapper;

    @Override
    public AiUsageDtos.Report report(LocalDate startDate, LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = startDate != null ? startDate : end.minusDays(DEFAULT_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "起始日期不能晚于结束日期（startDate=" + start + "，endDate=" + end + "）");
        }
        long span = end.toEpochDay() - start.toEpochDay() + 1;
        if (span > MAX_RANGE_DAYS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "查询区间最多 " + MAX_RANGE_DAYS + " 天，当前为 " + span + " 天");
        }

        // 半开区间：结束日期当天 23:59:59.999 也要算进来，所以 to 取次日零点
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay();
        List<ChatUsageRow> rows = usageMapper.selectDailyUsage(from, to);

        List<AiUsageDtos.DailyUsage> usages = new ArrayList<>(rows.size());
        long promptTotal = 0;
        long completionTotal = 0;
        long callsTotal = 0;
        for (ChatUsageRow row : rows) {
            long prompt = orZero(row.getPromptTokens());
            long completion = orZero(row.getCompletionTokens());
            long calls = orZero(row.getCalls());
            promptTotal += prompt;
            completionTotal += completion;
            callsTotal += calls;
            usages.add(new AiUsageDtos.DailyUsage(
                    row.getDay() == null ? "" : row.getDay().toString(),
                    modelName(row.getModelCode()),
                    prompt,
                    completion,
                    prompt + completion,
                    calls));
        }
        log.debug("AI 用量查询 {} ~ {}：{} 行，调用 {} 次", start, end, usages.size(), callsTotal);
        return new AiUsageDtos.Report(start.toString(), end.toString(), usages,
                new AiUsageDtos.Totals(promptTotal, completionTotal, promptTotal + completionTotal, callsTotal),
                AiUsageDtos.NOTE);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    /** 历史行可能没记模型（早期版本不写 model_code），显示成明确的占位而不是空白。 */
    private static String modelName(String modelCode) {
        return StringUtils.hasText(modelCode) ? modelCode.trim() : AiUsageDtos.UNKNOWN_MODEL;
    }
}
