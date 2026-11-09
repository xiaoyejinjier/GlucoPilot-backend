package com.threadfit.glucopilot.localization;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.ai.audit.service.TranslationAuditQuery;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.localization.entity.LocalizationAuditRow;
import com.threadfit.glucopilot.localization.mapper.LocalizationMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 翻译缓存的审计读实现：{@link TranslationAuditQuery} 的本地化侧落地。
 *
 * <h2>为什么实现放在这里</h2>
 * 缓存表 {@code content_localization} 归本模块，SQL 与「什么算 PENDING/READY」的语义都在这一侧；
 * 对外的路径（{@code /admin/v1/ai/audit/translations}）、权限（{@code ai:chat:audit}）
 * 与行结构（{@link AiAuditDtos.TranslationRow}）由 ai 模块拥有。依赖方向
 * {@code localization → ai} 不变。
 *
 * <h2>为什么只给预览</h2>
 * {@code ARTICLE_BODY} 的译文是整篇文章（可能几 KB）。列表给前 200 字 + 字符数，
 * 需要全文时按 {@code kind + resourceId + sourceHash} 直接定位数据库记录即可——
 * 译文正文不像用户对话正文那样敏感，所以本期不做「点开读全文 + 留痕」的第二段接口。
 */
@Component
@RequiredArgsConstructor
public class LocalizationAuditQueryImpl implements TranslationAuditQuery {

    /** 与 {@code AiAuditServiceImpl.RESULT_PREVIEW_CHARS} 保持一致，前端只需一套截断预期。 */
    static final int PREVIEW_CHARS = 200;

    private final LocalizationMapper mapper;

    @Override
    public PageResult<AiAuditDtos.TranslationRow> page(String kind, String status, PageQuery query) {
        IPage<LocalizationAuditRow> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<LocalizationAuditRow> result = mapper.selectAuditPage(page, kind, status);
        List<AiAuditDtos.TranslationRow> rows = result.getRecords().stream()
                .map(LocalizationAuditQueryImpl::toRow)
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    private static AiAuditDtos.TranslationRow toRow(LocalizationAuditRow row) {
        String json = row.getTranslatedJson();
        return new AiAuditDtos.TranslationRow(
                row.getKind(),
                row.getResourceId(),
                row.getSourceHash(),
                row.getLocale(),
                row.getStatus(),
                row.getPromptVer(),
                json == null ? 0 : json.length(),
                preview(json),
                row.getUpdatedAt());
    }

    /** 去首尾空白后截断；{@code PENDING}/{@code FAILED} 的译文为空，返回 null 由前端显示占位。 */
    private static String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String collapsed = text.strip();
        return collapsed.length() <= PREVIEW_CHARS
                ? collapsed
                : collapsed.substring(0, PREVIEW_CHARS) + "…";
    }
}
