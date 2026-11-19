package com.threadfit.glucopilot.ai.model.mapper;

import lombok.Data;

import java.time.LocalDate;

/**
 * 账单的「天 × 场景 × 模型」投影（V75）。
 *
 * <p>为什么是 POJO 而不是 {@code Map}：MySQL 原样返回列名、H2 会转成大写，按 Map 取键在
 * 生产与单测之间会错位（{@code day} vs {@code DAY}）——这类错位不会报错，只会让报表全零。
 *
 * <p>为什么不直接用 {@code record}：MyBatis 靠无参构造 + setter 填字段，record 没有无参构造。
 */
@Data
public class AiCallUsageRow {

    /** 日期（库里的 {@code CAST(gmt_create AS DATE)}；别名叫 usage_day，避开 H2 把 DAY 当区间关键字）。 */
    private LocalDate usageDay;

    /** 场景编码（{@code SceneCode.name()}）；早期或框架内部调用可能为空串。 */
    private String sceneCode;

    /** 模型编码（身份，不是 endpoint）；历史行可能为空。 */
    private String modelCode;

    private Long promptTokens;

    private Long completionTokens;

    /** 缓存命中 token；从没上报过命中时为 0（与 {@code chat_message} 的「NULL 表示未知」口径不同）。 */
    private Long cacheHitTokens;

    /** 缓存未命中 token。 */
    private Long cacheMissTokens;

    /** 调用次数。 */
    private Long calls;

    /** 失败调用次数（{@code ok = 0}）。 */
    private Long failedCalls;
}
