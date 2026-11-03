package com.threadfit.glucopilot.ai.model.mapper;

import lombok.Data;

import java.time.LocalDate;

/**
 * 「天 × 模型」的用量投影：只用于报表，不是表实体（没有对应表，也没有主键）。
 *
 * <p>用 POJO 而不是 {@code Map}：列名到字段的映射交给 MyBatis（**不要**自己按字符串键去 Map 里取——
 * 不同数据库对未加引号的别名大小写处理不一致，MySQL 小写、H2 大写，取值代码会随环境漂移）。
 */
@Data
public class ChatUsageRow {

    /** 日期（SQL 里 {@code CAST(gmt_create AS DATE)}）。 */
    private LocalDate day;

    /** 模型编码；库里可能为 NULL，SQL 已 COALESCE 成空串，服务层再决定怎么显示。 */
    private String modelCode;

    /** 输入 token 合计。 */
    private Long promptTokens;

    /** 输出 token 合计。 */
    private Long completionTokens;

    /** 助手消息条数，即调用次数。 */
    private Long calls;
}
