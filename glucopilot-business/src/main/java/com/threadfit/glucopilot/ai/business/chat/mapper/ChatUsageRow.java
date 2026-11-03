package com.threadfit.glucopilot.ai.business.chat.mapper;

import lombok.Data;

import java.time.LocalDate;

/**
 * 用量报表的一行（天 × 模型）。
 *
 * <h2>为什么是 POJO 而不是 {@code Map<String,Object>}</h2>
 * 同一条 SQL 在 MySQL 上列别名是小写、在 H2 上是全大写（未加引号时），用 {@code row.get("day")}
 * 取值会随环境漂移；绑定到类型化字段由 MyBatis 的自动映射处理，两边一致。
 *
 * <h2>为什么放在 mapper 包</h2>
 * 它是 {@link ChatMessageMapper#selectDailyUsage} 的返回类型——查询结果的行结构属于持久层，
 * 不是对外契约（对外契约是 {@code AiUsageDtos.DailyUsage}）。放在这里，改 SQL 与改行结构在同一个目录。
 */
@Data
public class ChatUsageRow {

    /** 日期（服务端时区按数据库会话时区，与 gmt_create 同源）。 */
    private LocalDate day;

    /** 模型编码；历史行的 model_code 为空串（SQL 里已 COALESCE）。 */
    private String modelCode;

    /** 输入 token 合计。 */
    private Long promptTokens;

    /** 输出 token 合计。 */
    private Long completionTokens;

    /** 调用次数（助手消息条数）。 */
    private Long calls;
}
