package com.threadfit.glucopilot.localization;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.localization.entity.LocalizationAuditRow;
import com.threadfit.glucopilot.localization.mapper.LocalizationMapper;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中英翻译的审计视图（只读），供管理端「AI 审计 → 翻译」页签使用。
 *
 * <h2>为什么在 localization 模块，却用 {@code ai:chat:audit} 权限</h2>
 * 缓存表 {@code content_localization} 属于本模块，SQL 也就留在这里（谁的表谁写 SQL）；
 * 但这一页是 AI 审计的一个页签——看的是「模型翻成了什么、用的是哪版提示词」。
 * 挂 {@code content:news:list} 会让「能看文章的人顺带看到翻译审计」，与原有边界不符。
 *
 * <h2>为什么只给译文预览</h2>
 * {@code ARTICLE_BODY} 的译文是整篇文章（可能几 KB）。列表只给 200 字预览 +
 * 字符数，需要全文时按 {@code kind + resourceId + sourceHash} 定位数据库记录即可；
 * 真要「点开读全文并留痕」，应当像会话审计那样单独开一个写审计的详情接口，
 * 本期不做（翻译正文不像用户对话正文那样敏感，优先级更低）。
 */
@RestController
@RequestMapping("/admin/v1/localizations/audit")
@RequiredArgsConstructor
public class AdminLocalizationAuditController {

    /** 与 {@code AiAuditServiceImpl.RESULT_PREVIEW_CHARS} 保持一致。 */
    static final int PREVIEW_CHARS = 200;

    private final LocalizationMapper mapper;

    /**
     * 翻译缓存分页：按 {@code updated_at} 倒序。
     *
     * @param kind   ARTICLE_PREVIEW / ARTICLE_BODY / PROFILE_NAME，可空
     * @param status PENDING / READY / FAILED，可空
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:chat:audit')")
    public ApiResponse<PageResult<Row>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @Valid PageQuery query) {
        IPage<LocalizationAuditRow> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<LocalizationAuditRow> result = mapper.selectAuditPage(
                page, normalize(kind), normalize(status));
        List<Row> rows = result.getRecords().stream()
                .map(row -> {
                    String json = row.getTranslatedJson();
                    return new Row(
                            row.getKind(),
                            row.getResourceId(),
                            row.getSourceHash(),
                            row.getLocale(),
                            row.getStatus(),
                            row.getPromptVer(),
                            json == null ? 0 : json.length(),
                            preview(json),
                            row.getUpdatedAt());
                })
                .toList();
        return ApiResponse.ok(PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows));
    }

    private static String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String collapsed = text.strip();
        return collapsed.length() <= PREVIEW_CHARS
                ? collapsed
                : collapsed.substring(0, PREVIEW_CHARS) + "…";
    }

    private static String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    /**
     * 审计行。
     *
     * @param sourceHash     原文内容的 sha256（含提示词版本前缀）——同一个 resourceId 出现多行，
     *                       说明原文变过或换过提示词版本
     * @param translatedChars 译文 JSON 的字符数；为 0 说明还在 PENDING 或翻译失败
     */
    public record Row(String kind,
                      String resourceId,
                      String sourceHash,
                      String locale,
                      String status,
                      String promptVer,
                      int translatedChars,
                      String translatedPreview,
                      java.time.LocalDateTime updatedAt) {
    }
}
