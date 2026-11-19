package com.threadfit.glucopilot.ai.model.service.impl;

import com.threadfit.glucopilot.ai.model.dto.AiUsageDtos;
import com.threadfit.glucopilot.ai.model.mapper.AiCallLogMapper;
import com.threadfit.glucopilot.ai.model.mapper.AiCallUsageRow;
import com.threadfit.glucopilot.ai.model.service.AiUsageService;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用量报表实现：读 {@code ai_call_log} 账本，按「天 × 场景 × 模型」投影并做区间校验。
 *
 * <h2>为什么把区间校验放在这里而不是 Controller</h2>
 * 区间规则（默认 7 天、上限 92 天、起止顺序）是<b>报表语义</b>，将来若从别处调用（定时汇总、
 * 导出）也应遵守同一套；放 Controller 会让「换个入口就绕过校验」。
 *
 * <h2>为什么在 Java 里算命中率而不是 SQL</h2>
 * 命中率要区分「0%」与「没有数据」，SQL 里得写成 {@code NULLIF(...)} 再靠 {@code Double}
 * 的空值传到前端；在 Java 里判一次和分母是否为 0，语义更直白，也方便四舍五入到固定位数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageServiceImpl implements AiUsageService {

    private final AiCallLogMapper callLogMapper;

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
        List<AiCallUsageRow> rows = callLogMapper.dailyUsage(from, to);

        List<AiUsageDtos.DailyUsage> usages = new ArrayList<>(rows.size());
        long promptTotal = 0;
        long completionTotal = 0;
        long hitTotal = 0;
        long missTotal = 0;
        long callsTotal = 0;
        long failedTotal = 0;
        for (AiCallUsageRow row : rows) {
            long prompt = orZero(row.getPromptTokens());
            long completion = orZero(row.getCompletionTokens());
            long hit = orZero(row.getCacheHitTokens());
            long miss = orZero(row.getCacheMissTokens());
            long calls = orZero(row.getCalls());
            long failed = orZero(row.getFailedCalls());
            promptTotal += prompt;
            completionTotal += completion;
            hitTotal += hit;
            missTotal += miss;
            callsTotal += calls;
            failedTotal += failed;
            usages.add(new AiUsageDtos.DailyUsage(
                    row.getUsageDay() == null ? "" : row.getUsageDay().toString(),
                    orEmpty(row.getSceneCode()),
                    sceneLabel(row.getSceneCode()),
                    modelName(row.getModelCode()),
                    prompt,
                    completion,
                    hit,
                    miss,
                    hitRate(hit, miss),
                    prompt + completion,
                    calls,
                    failed));
        }
        log.debug("AI 用量查询 {} ~ {}：{} 行，调用 {} 次，失败 {} 次",
                start, end, usages.size(), callsTotal, failedTotal);
        return new AiUsageDtos.Report(start.toString(), end.toString(), usages,
                new AiUsageDtos.Totals(promptTotal, completionTotal, hitTotal, missTotal,
                        hitRate(hitTotal, missTotal), promptTotal + completionTotal, callsTotal, failedTotal),
                AiUsageDtos.NOTE);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String orEmpty(String value) {
        return value == null ? AiUsageDtos.UNKNOWN_SCENE_CODE : value.trim();
    }

    /** 历史行可能没记模型（早期版本不写 model_code），显示成明确的占位而不是空白。 */
    private static String modelName(String modelCode) {
        return StringUtils.hasText(modelCode) ? modelCode.trim() : AiUsageDtos.UNKNOWN_MODEL;
    }

    /**
     * 场景编码转中文名。
     *
     * <p>未知编码（回滚后库里留了新版编码、或以后新增场景没来得及发版）<b>回显编码本身</b>：
     * 报表里看到 {@code MEAL_ANALYSIS_V2} 这样的字面量，人能立刻明白"这是新场景"；显示成
     * 「（未归属）」则会被误当成"记账丢了归属"。
     */
    private static String sceneLabel(String sceneCode) {
        if (!StringUtils.hasText(sceneCode)) {
            return AiUsageDtos.UNKNOWN_SCENE;
        }
        SceneCode scene = SceneCode.findByCode(sceneCode);
        return scene == null ? sceneCode.trim() : scene.getDisplayName();
    }

    /** 命中率：分子分母都为 0 时返回 null（表示"没上报"，而不是"0%"）。 */
    private static Double hitRate(long hit, long miss) {
        long denominator = hit + miss;
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(hit)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
