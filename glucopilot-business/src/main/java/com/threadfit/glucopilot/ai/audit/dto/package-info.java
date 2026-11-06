/**
 * AI 审计的响应契约：每张表一个行记录，全部是不可变 record。
 *
 * <p>刻意<b>不</b>复用各业务域自己的 DTO：审计要看的列和业务列表要看的列不一样
 * （例如审计要 {@code tool_name}/{@code duration_ms}/{@code error_type}，业务侧从来不展示），
 * 硬合并会让两边都背上用不到的字段。
 */
package com.threadfit.glucopilot.ai.audit.dto;
