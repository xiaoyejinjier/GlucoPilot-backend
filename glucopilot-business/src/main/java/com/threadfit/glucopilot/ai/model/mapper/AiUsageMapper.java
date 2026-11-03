package com.threadfit.glucopilot.ai.model.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量报表的只读查询。
 *
 * <h2>为什么单独一个 Mapper</h2>
 * 这条 SQL 跨表语义上属于「AI 用量统计」，但物理表是对话模块的 {@code chat_message}
 * （全项目只有它在写 token）。放在 {@code ChatMessageMapper} 里会让那个"写对话"的接口多出一个
 * 报表职责；单开一个只读 Mapper、只指向同一张表，职责更清楚，也不影响对话侧的改动。
 *
 * <h2>SQL 上的两个刻意选择</h2>
 * <ul>
 *   <li><b>按 {@code msg_role = 2} 过滤</b>：只有助手消息带 usage，用户消息的 token 恒为 NULL，
 *       不过滤会把调用次数算成两倍。</li>
 *   <li><b>时间窗用半开区间</b>（{@code >= from} 且 {@code < to}）：{@code to} 由服务层传
 *       "结束日期 + 1 天"，不写 {@code DATE_ADD}/{@code DATEADD}，避免 MySQL 与 H2 的语法差异。</li>
 * </ul>
 */
@Mapper
public interface AiUsageMapper {

    /**
     * 按「天 × 模型」汇总用量。
     *
     * @param from 起始时刻（含）
     * @param to   结束时刻（<b>不含</b>，服务层传结束日期次日零点）
     */
    @Select("""
            SELECT CAST(gmt_create AS DATE) AS day,
                   COALESCE(model_code, '')  AS model_code,
                   COALESCE(SUM(prompt_tokens), 0)     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COUNT(*)                            AS calls
            FROM chat_message
            WHERE is_deleted = 0
              AND msg_role = 2
              AND gmt_create >= #{from}
              AND gmt_create < #{to}
            GROUP BY CAST(gmt_create AS DATE), COALESCE(model_code, '')
            ORDER BY day DESC, model_code ASC
            """)
    List<ChatUsageRow> selectDailyUsage(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
