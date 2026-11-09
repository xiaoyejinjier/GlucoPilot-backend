package com.threadfit.glucopilot.ai.audit.service;

import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;

/**
 * 内容审核命中的审计读端口（由 {@code community} 模块实现）。
 *
 * <h2>为什么接口在 ai、实现却在 community</h2>
 * 这条数据对外是「AI 审计 → 内容审核」页签（路径 {@code /admin/v1/ai/audit/moderation-hits}、
 * 权限 {@code ai:chat:audit}），所以<b>契约属于 ai</b>：行结构与查询语义就在上面这些类型里，
 * 前端与文档只认这一份。
 *
 * <p>但表 {@code community_moderation_hit} 是社区的表，SQL 也就只能由社区来写
 * （谁的表谁写 SQL）。于是采用依赖倒置：ai 只声明「我要能查这个」，community 提供实现。
 * 依赖方向因此仍是 {@code community → ai}，{@code AiModuleBoundaryTest} 守的
 * 「ai 不反向依赖业务模块」不被破例——如果反过来把 SQL 搬进 ai，这条边界就破了。
 *
 * <h2>实现方必须保证</h2>
 * <ul>
 *   <li>返回的行<b>不含原文</b>：这张表本来就只存位置与片段哈希（V43 的隐私设计）；</li>
 *   <li>按主键倒序（最新判定在前），翻页时已看过的行不会被新写入挤到另一页；</li>
 *   <li>字符串过滤参数由调用方 trim 后传入；空白等于「不过滤」。</li>
 * </ul>
 */
public interface ModerationHitAuditQuery {

    /**
     * 命中明细分页。
     *
     * @param targetType   ARTICLE / COMMENT，null 表示不过滤
     * @param categoryCode 命中类别 code（如 {@code medical_advice}），null 表示不过滤
     * @param severity     1 低 / 2 中 / 3 高，null 表示不过滤
     */
    PageResult<AiAuditDtos.ModerationHitRow> page(String targetType, String categoryCode,
                                                  Integer severity, PageQuery query);
}
