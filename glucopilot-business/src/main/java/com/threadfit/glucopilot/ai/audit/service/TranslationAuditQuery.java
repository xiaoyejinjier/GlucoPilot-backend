package com.threadfit.glucopilot.ai.audit.service;

import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;

/**
 * 中英翻译缓存的审计读端口（由 {@code localization} 模块实现）。
 *
 * <h2>为什么接口在 ai、实现却在 localization</h2>
 * 与 {@link ModerationHitAuditQuery} 同一套理由：对外是「AI 审计 → 翻译」页签
 * （路径 {@code /admin/v1/ai/audit/translations}、权限 {@code ai:chat:audit}），
 * 契约归 ai；表 {@code content_localization} 归 localization，SQL 与缓存语义留在那边。
 * 依赖方向仍是 {@code localization → ai}。
 *
 * <h2>实现方必须保证</h2>
 * <ul>
 *   <li><b>只给预览</b>：{@code ARTICLE_BODY} 的译文可能几 KB，行里只放前 200 字与字符数；</li>
 *   <li>按 {@code updated_at} 倒序（最近翻译的在最前），并列时按 {@code kind}、{@code resource_id} 稳定排序；</li>
 *   <li>字符串过滤参数由调用方 trim 后传入；空白等于「不过滤」。</li>
 * </ul>
 */
public interface TranslationAuditQuery {

    /**
     * 翻译缓存分页。
     *
     * @param kind   ARTICLE_PREVIEW / ARTICLE_BODY / PROFILE_NAME，null 表示不过滤
     * @param status PENDING / READY / FAILED，null 表示不过滤
     */
    PageResult<AiAuditDtos.TranslationRow> page(String kind, String status, PageQuery query);
}
