package com.threadfit.glucopilot.community.draft.service;

import static com.threadfit.glucopilot.community.draft.dto.CommunityDraftDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.article.service.CommunityPublicationService;
import com.threadfit.glucopilot.community.draft.mapper.CommunityDraftMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CommunityDraftService {
    private final CommunityDraftMapper mapper;
    private final JsonUtils json;
    private final CommunityPublicationService publication;
    private final ArticleAccessService access;

    public CommunityDraftService(
            CommunityDraftMapper mapper,
            JsonUtils json,
            CommunityPublicationService publication,
            ArticleAccessService access) {
        this.mapper = mapper;
        this.json = json;
        this.publication = publication;
        this.access = access;
    }

    private BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
    }

    private BizException conflict() {
        return new BizException(ErrorCode.CONFLICT, "草稿已变化，请刷新后重试");
    }

    private void user(long user, boolean lock) {
        if (mapper.selectActiveUser(user, lock).isEmpty()) throw missing();
    }

    private List<String> strings(Object raw) {
        return List.of(json.fromJson(raw.toString(), String[].class));
    }

    private LocalDateTime draftTime(Object value) {
        return dateTime(value);
    }

    private Draft map(Map<String, Object> r) {
        return new Draft(
                r.get("id").toString(),
                r.get("title").toString(),
                r.get("body").toString(),
                strings(r.get("tag_codes")),
                strings(r.get("custom_tags")),
                strings(r.get("image_ids")).stream()
                        .map(PublicationDtos.ArticleImage::new)
                        .toList(),
                ((Number) r.get("revision")).intValue(),
                r.get("source_article_id") == null ? null : r.get("source_article_id").toString(),
                r.get("source_revision") == null
                        ? null
                        : ((Number) r.get("source_revision")).intValue(),
                draftTime(r.get("updated_at")),
                strings(r.getOrDefault("video_ids", "[]")).stream()
                        .map(PublicationDtos.ArticleImage::new)
                        .toList(),
                Objects.toString(r.get("visibility"), "PUBLIC"),
                r.get("viewer_ids") == null ? List.of() : strings(r.get("viewer_ids")));
    }

    private Map<String, Object> row(long user, long id, boolean lock, boolean closed) {
        var rows = mapper.selectOwnedDraft(id, user, closed, lock);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    public Draft get(long user, long id) {
        user(user, false);
        return map(row(user, id, false, false));
    }

    public PageResult<Draft> list(long user, PageQuery q) {
        user(user, false);
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
        long total = mapper.countDrafts(user);
        return PageResult.of(
                q.getPageNum(),
                q.getPageSize(),
                total,
                mapper.selectDrafts(user, q.getPageSize(), q.offset()).stream()
                        .map(this::map)
                        .toList());
    }

    private Payload normalize(long user, Payload p) {
        if (p == null
                || p.clientRequestId() == null
                || !p.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))
            throw new BizException(ErrorCode.BAD_REQUEST, "请求标识不合法");
        String title = Objects.toString(p.title(), "").trim(),
                body = Objects.toString(p.body(), "");
        var codes = p.tagCodes() == null ? List.<String>of() : p.tagCodes();
        var custom = p.customTags() == null ? List.<String>of() : p.customTags();
        if (title.length() > 80
                || body.length() > 8000
                || codes.size() + custom.size() > 3
                || codes.stream().anyMatch(x -> x == null || !x.matches("[A-Za-z0-9_-]{1,32}"))
                || custom.stream()
                        .anyMatch(x -> x == null || !x.matches("[\\p{L}\\p{N} _-]{1,16}")))
            throw new BizException(ErrorCode.BAD_REQUEST, "草稿标题、正文或标签过长/不合法");
        publication.validateDraftTags(codes, custom);
        var images =
                publication.images(
                        user,
                        new PublicationDtos.Submit(
                                title,
                                body,
                                codes,
                                custom,
                                p.clientRequestId(),
                                p.sourceRevision(),
                                p.imageMediaIds()));
        var videos =
                publication.videos(
                        user,
                        new PublicationDtos.Submit(
                                title,
                                body,
                                codes,
                                custom,
                                p.clientRequestId(),
                                p.sourceRevision(),
                                p.imageMediaIds(),
                                p.videoMediaIds()));
        if (p.sourceArticleId() != null) {
            var source = publication.get(user, p.sourceArticleId(), false);
            if (!source.mine() || p.sourceRevision() == null || p.sourceRevision() < 1)
                throw missing();
        }
        var sourceScope =
                p.visibility() == null && p.sourceArticleId() != null
                        ? publication.get(user, p.sourceArticleId(), false)
                        : null;
        var scope =
                access.normalize(
                        user,
                        sourceScope == null ? p.visibility() : sourceScope.visibility(),
                        sourceScope == null ? p.viewerIds() : sourceScope.viewerIds());
        return new Payload(
                title,
                body,
                List.copyOf(codes),
                List.copyOf(custom),
                images,
                p.clientRequestId(),
                p.revision(),
                p.sourceArticleId(),
                p.sourceRevision(),
                videos,
                scope.visibility(),
                scope.viewerIds());
    }

    private boolean same(Draft d, Payload p) {
        return d.visibility().equals(p.visibility())
                && d.viewerIds().equals(p.viewerIds())
                && d.videos().stream()
                        .map(PublicationDtos.ArticleImage::mediaId)
                        .toList()
                        .equals(p.videoMediaIds())
                && d.title().equals(p.title())
                && d.body().equals(p.body())
                && d.tagCodes().equals(p.tagCodes())
                && d.customTags().equals(p.customTags())
                && d.images().stream()
                        .map(PublicationDtos.ArticleImage::mediaId)
                        .toList()
                        .equals(p.imageMediaIds())
                && Objects.equals(
                        d.sourceArticleId(),
                        p.sourceArticleId() == null ? null : p.sourceArticleId().toString())
                && Objects.equals(d.sourceRevision(), p.sourceRevision());
    }

    @Transactional
    public Draft create(long user, Payload raw) {
        user(user, true);
        var p = normalize(user, raw);
        var prior = mapper.selectByRequest(user, p.clientRequestId());
        if (!prior.isEmpty()) {
            var saved = map(prior.getFirst());
            if (((Number) prior.getFirst().get("is_deleted")).intValue() != 0 || !same(saved, p))
                throw conflict();
            return saved;
        }
        if (mapper.countDraftsForCreate(user) >= 100)
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "草稿箱已满，请清理后再保存");
        mapper.insertDraft(
                user,
                p.title(),
                p.body(),
                json.toJson(p.tagCodes()),
                json.toJson(p.customTags()),
                json.toJson(p.imageMediaIds()),
                p.sourceArticleId(),
                p.sourceRevision(),
                p.clientRequestId(),
                p.clientRequestId(),
                json.toJson(p.videoMediaIds()),
                p.visibility(),
                json.toJson(p.viewerIds()));
        return map(mapper.selectCreatedDraft(user, p.clientRequestId()));
    }

    @Transactional
    public Draft edit(long user, long id, Payload raw) {
        user(user, true);
        var p = normalize(user, raw);
        var r = row(user, id, true, false);
        var current = map(r);
        if (p.revision() == null) throw conflict();
        if (p.revision() != current.revision()) {
            if (p.revision() == current.revision() - 1
                    && p.clientRequestId().equals(r.get("last_request_id"))
                    && same(current, p)) return current;
            throw conflict();
        }
        if (!Objects.equals(
                        current.sourceArticleId(),
                        p.sourceArticleId() == null ? null : p.sourceArticleId().toString())
                || !Objects.equals(current.sourceRevision(), p.sourceRevision())) throw conflict();
        mapper.updateDraft(
                p.title(),
                p.body(),
                json.toJson(p.tagCodes()),
                json.toJson(p.customTags()),
                json.toJson(p.imageMediaIds()),
                json.toJson(p.videoMediaIds()),
                p.visibility(),
                json.toJson(p.viewerIds()),
                p.clientRequestId(),
                id);
        return get(user, id);
    }

    @Transactional
    public void delete(long user, long id, int revision) {
        user(user, true);
        var r = row(user, id, true, true);
        if (((Number) r.get("is_deleted")).intValue() == 1) return;
        if (((Number) r.get("revision")).intValue() != revision) throw conflict();
        mapper.softDeleteDraft(id);
    }

    @Transactional
    public PublicationDtos.Article publish(long user, long id, Publish p) {
        user(user, true);
        var r = row(user, id, true, true);
        var d = map(r);
        if (p == null
                || p.clientRequestId() == null
                || !p.clientRequestId().matches("[A-Za-z0-9_-]{8,64}")
                || p.revision() != d.revision()) throw conflict();
        if (r.get("published_news_id") != null) {
            if (!p.clientRequestId().equals(r.get("publish_request_id"))) throw conflict();
            return publication.get(user, ((Number) r.get("published_news_id")).longValue(), false);
        }
        if (((Number) r.get("is_deleted")).intValue() != 0) throw missing();
        var request =
                new PublicationDtos.Submit(
                        d.title(),
                        d.body(),
                        d.tagCodes(),
                        d.customTags(),
                        p.clientRequestId(),
                        d.sourceRevision(),
                        d.images().stream().map(PublicationDtos.ArticleImage::mediaId).toList(),
                        d.videos().stream().map(PublicationDtos.ArticleImage::mediaId).toList(),
                        d.visibility(),
                        d.viewerIds());
        var saved =
                d.sourceArticleId() == null
                        ? publication.submit(user, request)
                        : publication.edit(user, Long.parseLong(d.sourceArticleId()), request);
        mapper.markPublished(Long.parseLong(saved.id()), p.clientRequestId(), id);
        return saved;
    }
}
