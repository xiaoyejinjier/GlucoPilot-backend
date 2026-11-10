package com.threadfit.glucopilot.community.article.service;

import static com.threadfit.glucopilot.community.article.dto.PublicationDtos.*;
import static com.threadfit.glucopilot.community.comment.dto.AdminCommentDtos.*;
import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos.Article;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos.ArticleImage;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos.Review;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos.Submit;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos.Tag;
import com.threadfit.glucopilot.community.article.mapper.CommunityArticleQuery;
import com.threadfit.glucopilot.community.article.mapper.CommunityPublicationMapper;
import com.threadfit.glucopilot.community.comment.dto.AdminCommentDtos.AdminComment;
import com.threadfit.glucopilot.community.profile.dto.ProfileDtos;
import com.threadfit.glucopilot.community.recommendation.service.CommunityRecommendationService;
import com.threadfit.glucopilot.community.report.dto.ReportDtos.ReportRequest;
import com.threadfit.glucopilot.community.report.dto.ReportDtos.Resolution;
import com.threadfit.glucopilot.community.search.TitlePinyin;
import com.threadfit.glucopilot.community.shared.service.CommunityService;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CommunityPublicationService {
    private final com.threadfit.glucopilot.community.report.service.CommunityReportService reportService;
    private final com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService commentAdmin;
    private final CommunityPublicationMapper mapper;
    private final CmsNewsMapper news;
    private final CommunityService community;
    private final ArticleAccessService access;
    private final CommunityRecommendationService recommendation;

    public CommunityPublicationService(
            CommunityPublicationMapper mapper,
            CmsNewsMapper news,
            CommunityService community,
            ArticleAccessService access,
            CommunityRecommendationService recommendation,
            com.threadfit.glucopilot.community.report.service.CommunityReportService reportService,
            com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService commentAdmin) {
        this.commentAdmin = commentAdmin;
        this.reportService = reportService;
        this.mapper = mapper;
        this.news = news;
        this.community = community;
        this.access = access;
        this.recommendation = recommendation;
    }

    private static final Set<String> STATES = Set.of(
            "AI_PENDING",
            "AI_REVIEWING",
            "PENDING_ADMIN",
            "AI_REJECTED",
            "AI_ERROR",
            "PUBLISHED",
            "REJECTED",
            "HIDDEN");

    private BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "文章或举报不存在");
    }

    private BizException conflict() {
        return new BizException(ErrorCode.CONFLICT, "内容状态已变化，请刷新后重试");
    }

    private void user(long id, boolean lock) {
        var ids = mapper.selectActiveUser(id, lock);
        if (ids.isEmpty())
            throw missing();
    }

    private void page(PageQuery q) {
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
    }

    public List<Tag> tags() {
        return tags(null);
    }

    public List<Tag> tags(Long viewer) {
        return mapper.selectVisibleTags(viewer).stream()
                .map(rs -> new Tag(text(rs, "code"), text(rs, "name")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public List<Tag> tags(long id) {
        return mapper.selectArticleTags(id).stream()
                .map(rs -> new Tag(text(rs, "code"), text(rs, "name")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private List<String> codes(Submit request) {
        if (request.title() == null
                || request.title().trim().isEmpty()
                || request.title().trim().length() > 80
                || request.body() == null
                || (request.body().trim().length() < 20
                        && (request.imageMediaIds() == null || request.imageMediaIds().isEmpty())
                        && (request.videoMediaIds() == null || request.videoMediaIds().isEmpty()))
                || request.body().length() > 8000
                || request.clientRequestId() == null
                || !request.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))
            throw new BizException(ErrorCode.BAD_REQUEST, "标题、正文或请求标识不合法");
        List<String> predefined = request.tagCodes() == null ? List.of() : request.tagCodes();
        List<String> custom = request.customTags() == null ? List.of() : request.customTags();
        if (predefined.size() > 3 || custom.size() > 3 || predefined.size() + custom.size() < 1)
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择或填写1～3个标签");
        Set<String> allowed = new HashSet<>(mapper.lockEnabledTagCodes());
        if (predefined.stream().anyMatch(code -> code == null || !allowed.contains(code)))
            throw new BizException(ErrorCode.BAD_REQUEST, "标签不存在或已停用");
        Set<String> resolved = new TreeSet<>(predefined);
        for (String raw : custom)
            resolved.add(customTag(raw));
        if (resolved.size() > 3)
            throw new BizException(ErrorCode.BAD_REQUEST, "最多选择3个标签");
        return List.copyOf(resolved);
    }

    /** 官方资讯保存：预置标签与自定义标签合计去重后为1～3个。 */
    @Transactional
    public List<String> replaceOfficialTags(
            long newsId, List<String> tagCodes, List<String> customTags) {
        List<String> predefined = tagCodes == null ? List.of() : tagCodes;
        List<String> custom = customTags == null ? List.of() : customTags;
        if (predefined.size() > 3 || custom.size() > 3 || predefined.size() + custom.size() < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择或填写1～3个标签");
        }
        Set<String> allowed = new HashSet<>(mapper.lockEnabledTagCodes());
        if (predefined.stream()
                .anyMatch(
                        code -> code == null || code.isBlank() || !allowed.contains(code.trim()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标签不存在或已停用");
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String code : predefined) {
            resolved.add(code.trim());
        }
        for (String raw : custom) {
            resolved.add(customTag(raw));
        }
        if (resolved.isEmpty() || resolved.size() > 3) {
            throw new BizException(ErrorCode.BAD_REQUEST, "最多选择3个标签");
        }
        List<String> codes = List.copyOf(resolved);
        saveTags(newsId, codes);
        return codes;
    }

    public void validateDraftTags(List<String> codes, List<String> custom) {
        Set<String> allowed = new HashSet<>(mapper.lockDraftTagCodes());
        if (codes.stream().anyMatch(code -> !allowed.contains(code)))
            throw new BizException(ErrorCode.BAD_REQUEST, "标签已删除或停用，请重新选择标签");
        for (String raw : custom) {
            String name = java.text.Normalizer.normalize(
                    Objects.toString(raw, ""), java.text.Normalizer.Form.NFKC)
                    .trim();
            if (mapper.countDisabledTagName(name) > 0)
                throw new BizException(ErrorCode.BAD_REQUEST, "标签已删除或停用，请重新选择标签");
        }
    }

    private String customTag(String raw) {
        String name = java.text.Normalizer.normalize(
                Objects.toString(raw, ""), java.text.Normalizer.Form.NFKC)
                .replaceFirst("^\\s*#+", "")
                .trim()
                .replaceAll("\\s+", " ");
        if (!name.matches("[\\p{L}\\p{N} _-]{1,16}"))
            throw new BizException(ErrorCode.BAD_REQUEST, "标签限1～16字，可用中文、字母和数字");
        var existing = mapper.selectCustomTag(name);
        if (!existing.isEmpty()) {
            if (((Number) existing.getFirst().get("enabled")).intValue() != 1)
                throw new BizException(ErrorCode.BAD_REQUEST, "该标签已停用");
            return existing.getFirst().get("code").toString();
        }
        String code = "custom_"
                + org.springframework.util.DigestUtils.md5DigestAsHex(
                        name.toLowerCase(Locale.ROOT)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring(0, 24);
        mapper.insertCustomTag(code, name);
        if (mapper.selectTagEnabled(code) != 1)
            throw new BizException(ErrorCode.BAD_REQUEST, "该标签已停用");
        return code;
    }

    private void saveTags(long id, List<String> codes) {
        mapper.deleteArticleTags(id);
        for (String code : codes)
            mapper.insertArticleTag(id, code);
    }

    private boolean same(Article article, Submit request, List<String> codes) {
        var scope = request.visibility() == null
                ? null
                : access.normalize(
                        Long.parseLong(article.author().userId()),
                        request.visibility(),
                        request.viewerIds());
        return (scope == null
                || (article.visibility().equals(scope.visibility())
                        && article.viewerIds().equals(scope.viewerIds())))
                && article.title().equals(request.title().trim())
                && article.body().equals(request.body().trim())
                && article.tags().stream().map(Tag::code).sorted().toList().equals(codes)
                && article.videos().stream()
                        .map(ArticleImage::mediaId)
                        .toList()
                        .equals(
                                request.videoMediaIds() == null
                                        ? List.of()
                                        : request.videoMediaIds())
                && article.images().stream()
                        .map(ArticleImage::mediaId)
                        .toList()
                        .equals(
                                request.imageMediaIds() == null
                                        ? List.of()
                                        : request.imageMediaIds());
    }

    public List<String> images(long author, Submit request) {
        List<String> ids = request.imageMediaIds() == null ? List.of() : request.imageMediaIds();
        if (ids.size() > 6 || new HashSet<>(ids).size() != ids.size())
            throw new BizException(ErrorCode.BAD_REQUEST, "最多6张配图，不能重复");
        for (String id : ids) {
            try {
                if (id == null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) < 1)
                    throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图片标识不合法");
            }
            if (mapper.countOwnedImage(Long.parseLong(id), author) != 1)
                throw new BizException(ErrorCode.BAD_REQUEST, "配图不存在、不可用或不属于当前账号，请重新上传");
        }
        return List.copyOf(ids);
    }

    public List<String> videos(long author, Submit request) {
        List<String> ids = request.videoMediaIds() == null ? List.of() : request.videoMediaIds();
        if (ids.size() > 1)
            throw new BizException(ErrorCode.BAD_REQUEST, "最多上传1个视频");
        for (String id : ids) {
            try {
                if (id == null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) < 1)
                    throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, "视频标识不合法");
            }
            if (mapper.countOwnedVideo(Long.parseLong(id), author) != 1)
                throw new BizException(ErrorCode.BAD_REQUEST, "视频不可用或不属于当前账号，请重新上传");
        }
        return List.copyOf(ids);
    }

    private void saveMedia(long id, List<String> images, List<String> videos) {
        var ids = new ArrayList<>(images);
        ids.addAll(videos);
        saveImages(id, ids);
    }

    private void saveImages(long id, List<String> images) {
        mapper.deleteArticleMedia(id);
        for (int i = 0; i < images.size(); i++)
            mapper.insertArticleMedia(id, Long.parseLong(images.get(i)), i);
    }

    @Transactional
    public Article submit(long author, Submit request) {
        user(author, true);
        List<String> codes = codes(request);
        List<String> images = images(author, request);
        List<String> videos = videos(author, request);
        var scope = access.normalize(author, request.visibility(), request.viewerIds());
        var prior = mapper.selectSubmittedArticleIds(author, request.clientRequestId());
        if (!prior.isEmpty()) {
            var saved = get(author, prior.getFirst(), false);
            if (!same(saved, request, codes))
                throw conflict();
            return saved;
        }
        if (mapper.countRecentArticles(author, LocalDateTime.now().minusHours(1)) >= 5)
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "投稿较频繁，请稍后再试");
        var bloggers = mapper.selectBloggerIds(author);
        if (bloggers.isEmpty())
            mapper.insertUserBlogger(author);
        Long blogger = bloggers.isEmpty() ? mapper.selectUserBlogger(author) : bloggers.getFirst();
        CmsNews row = new CmsNews();
        String title = request.title().trim();
        row.setTitle(title);
        row.setTitlePinyin(TitlePinyin.full(title));
        row.setTitlePinyinInitials(TitlePinyin.initials(title));
        row.setContentBody(request.body().trim());
        row.setSummaryText(summary(request.body()));
        row.setChannelCode(4);
        row.setContentType(videos.isEmpty() ? 1 : 2);
        row.setCoverUrl("");
        row.setPublishStatus(0);
        row.setPublishAt(LocalDateTime.now());
        row.setLikeCount(0);
        row.setCollectCount(0);
        row.setBloggerId(blogger);
        row.setCommunityManaged(1);
        news.insert(row);
        mapper.insertSubmission(row.getId(), author, request.clientRequestId());
        access.saveScope(row.getId(), scope);
        saveTags(row.getId(), codes);
        saveMedia(row.getId(), images, videos);
        log("ARTICLE", row.getId(), 1, "USER", author, "SUBMIT", "");
        return get(author, row.getId(), false);
    }

    public static String summary(String body) {
        String text = body.trim().replaceAll("\\s+", " ");
        return text.substring(0, Math.min(140, text.length()));
    }

    @Transactional
    public Article edit(long author, long id, Submit request) {
        user(author, true);
        List<String> codes = codes(request);
        List<String> images = images(author, request);
        List<String> videos = videos(author, request);
        var state = lock(id);
        if (!Objects.equals(number(state.get("author_user_id")), author))
            throw missing();
        var scope = request.visibility() == null
                ? null
                : access.normalize(author, request.visibility(), request.viewerIds());
        int revision = ((Number) state.get("revision")).intValue();
        if (request.revision() == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少文章版本");
        if (request.revision() != revision) {
            Article current = get(author, id, false);
            if (request.revision() == revision - 1 && same(current, request, codes))
                return current;
            throw conflict();
        }
        String title = request.title().trim();
        mapper.updateArticleContent(
                title,
                TitlePinyin.full(title),
                TitlePinyin.initials(title),
                summary(request.body()),
                request.body().trim(),
                videos.isEmpty() ? 1 : 2,
                id);
        mapper.resetArticleReview(id);
        if (scope != null)
            access.saveScope(id, scope);
        saveTags(id, codes);
        saveMedia(id, images, videos);
        log("ARTICLE", id, revision + 1, "USER", author, "RESUBMIT", "");
        return get(author, id, false);
    }

    public PageResult<Article> feed(
            long viewer, String tag, boolean following, String keyword, PageQuery q) {
        return feed(viewer, tag, following, keyword, "RECOMMEND", q);
    }

    public PageResult<Article> feed(
            long viewer, String tag, boolean following, String keyword, String sort, PageQuery q) {
        user(viewer, false);
        page(q);
        if (sort == null || !Set.of("RECOMMEND", "LATEST").contains(sort))
            throw new BizException(ErrorCode.BAD_REQUEST, "排序方式应为RECOMMEND或LATEST");
        if (keyword != null && !keyword.isBlank() && keyword.length() > 80)
            throw new BizException(ErrorCode.BAD_REQUEST, "搜索词最多80字");
        var query = new CommunityArticleQuery();
        query.setPublished(true);
        query.setReadable(true);
        query.setViewer(viewer);
        query.setTag(blankToNull(tag));
        query.setFollowing(following);
        query.setKeyword(searchKeyword(keyword));
        // 含义是：
        // - 用户选择推荐排序；
        // - 当前不是只看关注作者的列表；
        // - 当前没有输入搜索关键词。
        // 满足这些条件，就为当前用户生成推荐上下文。
        if ("RECOMMEND".equals(sort) && !following && query.getKeyword() == null)
            query.setRecommendation(recommendation.prepare(viewer));

        return list(viewer, false, query, q);
    }

    public List<Tag> myTags(long viewer) {
        return myTags(viewer, false);
    }

    public List<Tag> myTags(long viewer, boolean includeDisabled) {
        user(viewer, false);
        return mapper.selectAuthorTags(viewer, includeDisabled ? 1 : 0).stream()
                .map(rs -> new Tag(text(rs, "code"), text(rs, "name"), intValue(rs, "enabled")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public PageResult<Article> byAuthor(long viewer, String key, PageQuery q) {
        user(viewer, false);
        page(q);
        var profile = community.profile(viewer, key);
        var query = new CommunityArticleQuery();
        query.setPublished(true);
        query.setReadable(true);
        query.setViewer(viewer);
        if (profile.userId() != null)
            query.setAuthorUser(Long.parseLong(profile.userId()));
        else
            query.setBlogger(Long.parseLong(key.substring(1)));
        return list(viewer, false, query, q);
    }

    public PageResult<Article> mine(long viewer, PageQuery q) {
        user(viewer, false);
        page(q);
        var query = new CommunityArticleQuery();
        query.setMine(viewer);
        return list(viewer, true, query, q);
    }

    public PageResult<Article> queue(String status, PageQuery q) {
        return adminArticles(status, null, null, q);
    }

    public PageResult<Article> adminArticles(
            String status, String source, String keyword, PageQuery q) {
        page(q);
        if (status != null
                && !status.isBlank()
                && !STATES.contains(status)
                && !"DRAFT".equals(status))
            throw new BizException(ErrorCode.BAD_REQUEST, "文章状态不合法");
        if (source != null
                && !source.isBlank()
                && !Set.of("OFFICIAL", "COMMUNITY").contains(source))
            throw new BizException(ErrorCode.BAD_REQUEST, "文章来源不合法");
        if (keyword != null && keyword.trim().length() > 80)
            throw new BizException(ErrorCode.BAD_REQUEST, "搜索词最多80字");
        var query = new CommunityArticleQuery();
        query.setState(blankToNull(status));
        query.setSource(blankToNull(source));
        query.setKeyword(searchKeyword(keyword));
        query.setAdminSearch(true);
        return list(null, true, query, q);
    }

    private PageResult<Article> list(
            Long viewer, boolean privateData, CommunityArticleQuery query, PageQuery q) {
        long total = mapper.countArticles(query);
        var rows = mapper.selectArticles(query, q.getPageSize(), q.offset()).stream()
                .map(row -> map(row, viewer, privateData, false))
                .toList();
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    /**
     * 热榜灌装：一次 IN + published/readable 过滤，按 orderedIds 保序取满 limit。
     * 卡片口径与 feed 列表一致（无正文）。
     */
    public List<Article> listForHotRanking(long viewer, List<Long> orderedIds, int limit) {
        user(viewer, false);
        if (orderedIds == null || orderedIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        var ids = orderedIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var query = new CommunityArticleQuery();
        query.setPublished(true);
        query.setReadable(true);
        query.setViewer(viewer);
        query.setNewsIds(ids);
        var byId = new HashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : mapper.selectArticlesByIds(query)) {
            byId.put(longValue(row, "id"), row);
        }
        var out = new ArrayList<Article>(Math.min(limit, ids.size()));
        for (Long id : orderedIds) {
            if (out.size() >= limit) {
                break;
            }
            var row = byId.get(id);
            if (row == null) {
                continue;
            }
            out.add(map(row, viewer, false, false));
        }
        return out;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** 搜索词小写化，便于匹配 title_pinyin / initials。 */
    private static String searchKeyword(String text) {
        String value = blankToNull(text);
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    public Article get(Long viewer, long id, boolean admin) {
        if (!admin && viewer != null)
            user(viewer, false);
        var rows = mapper.selectArticle(id).stream()
                .map(rs -> map(rs, viewer, true, true))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (rows.isEmpty())
            throw missing();
        Article item = rows.getFirst();
        if (!admin && !item.mine())
            access.requireReadable(viewer, id);
        if (admin || item.mine())
            return item;
        return map(mapper.selectPublicArticle(id), viewer, false, true);
    }

    private boolean isPublic(long id) {
        return mapper.countPublicArticle(id) == 1;
    }

    private Article map(Map<String, Object> rs, Long viewer, boolean privateData, boolean body) {
        long id = longValue(rs, "id");
        Long author = number(value(rs, "author_user_id"));
        boolean mine = viewer != null && Objects.equals(viewer, author);
        var policy = access.policy(id, mine);
        var flags = viewer == null
                ? List.<Map<String, Object>>of()
                : mapper.selectArticleActions(id, viewer);
        boolean liked = !flags.isEmpty() && ((Number) flags.getFirst().get("is_liked")).intValue() == 1;
        boolean collected = !flags.isEmpty() && ((Number) flags.getFirst().get("is_collected")).intValue() == 1;
        return new Article(
                Long.toString(id),
                text(rs, "title"),
                text(rs, "summary_text"),
                body ? text(rs, "content_body") : "",
                text(rs, "cover_url"),
                tags(id),
                articleAuthor(viewer, id, author, privateData),
                text(rs, "article_state"),
                intValue(rs, "revision"),
                privateData ? text(rs, "ai_verdict") : null,
                privateData ? text(rs, "ai_reason") : null,
                privateData ? text(rs, "ai_model") : null,
                privateData ? text(rs, "review_note") : null,
                mine,
                liked,
                collected,
                longValue(rs, "like_count"),
                longValue(rs, "collect_count"),
                community.commentCount(id),
                time(rs, "article_created_at"),
                time(rs, "publish_at"),
                intValue(rs, "community_managed") == 1 ? "COMMUNITY" : "OFFICIAL",
                community.articleImages(id),
                community.articleVideos(id),
                access.views(id),
                policy.visibility(),
                policy.revision(),
                policy.viewerIds());
    }

    private ProfileDtos.Profile articleAuthor(
            Long viewer, long id, Long author, boolean privateData) {
        if (author == null)
            return community.newsAuthor(viewer, id);
        if (!privateData) {
            try {
                return community.profile(viewer, "u" + author);
            } catch (BizException ex) {
                return null;
            }
        }
        var profiles = mapper.selectAuthorProfile(author).stream()
                .map(
                        rs -> new ProfileDtos.Profile(
                                "u" + author,
                                author.toString(),
                                Objects.toString(text(rs, "nickname"), "社区用户"),
                                Objects.toString(text(rs, "avatar_url"), ""),
                                "",
                                false,
                                Objects.equals(viewer, author)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return profiles.isEmpty()
                ? new ProfileDtos.Profile(
                        "u" + author,
                        author.toString(),
                        "社区用户",
                        "",
                        "",
                        false,
                        Objects.equals(viewer, author))
                : profiles.getFirst();
    }

    private Map<String, Object> lock(long id) {
        var rows = mapper.lockSubmission(id);
        if (rows.isEmpty())
            throw missing();
        return rows.getFirst();
    }

    @Transactional
    public Article review(long admin, long id, Review request) {
        var state = lock(id);
        int revision = ((Number) state.get("revision")).intValue();
        if (revision != request.revision())
            throw conflict();
        String current = (String) state.get("state"),
                decision = request.decision(),
                note = Objects.toString(request.note(), "").trim();
        if (note.length() > 1000 || (!"APPROVE".equals(decision) && note.isBlank()))
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写审核意见");
        String next;
        if ("APPROVE".equals(decision)
                && "PENDING_ADMIN".equals(current)
                && "PASS".equals(state.get("ai_verdict")))
            next = "PUBLISHED";
        else if ("REJECT".equals(decision)
                && Set.of("PENDING_ADMIN", "AI_REJECTED", "AI_ERROR").contains(current))
            next = "REJECTED";
        else if ("HIDE".equals(decision) && "PUBLISHED".equals(current))
            next = "HIDDEN";
        else
            throw new BizException(ErrorCode.CONFLICT, "仅初审通过的当前版本可终审发布，请刷新状态");
        mapper.updateReview(next, note, admin, id);
        mapper.updatePublicationStatus(next.equals("PUBLISHED") ? 1 : 2, id);
        log("ARTICLE", id, revision, "ADMIN", admin, decision, note);
        return get(null, id, true);
    }

    @Transactional
    public void retry(long actor, long id, boolean admin) {
        var row = lock(id);
        if (!admin && !Objects.equals(number(row.get("author_user_id")), actor))
            throw missing();
        if (!"AI_ERROR".equals(row.get("state")))
            throw conflict();
        mapper.retryReview(id);
        log(
                "ARTICLE",
                id,
                ((Number) row.get("revision")).intValue(),
                admin ? "ADMIN" : "USER",
                actor,
                "RETRY_AI",
                "");
    }

    /** 兼容文章调用方；具体实现已归入report功能。 */
    @Transactional
    public Map<String, Object> report(long viewer, ReportRequest request) {
        return reportService.report(viewer, request);
    }

    /** 兼容文章调用方；具体实现已归入report功能。 */
    public PageResult<Map<String, Object>> reports(String state, PageQuery q) {
        return reportService.reports(state, q);
    }

    /** 兼容文章调用方；具体实现已归入comment功能。 */
    public PageResult<AdminComment> adminComments(
            String status, String verdict, Integer hidden, String keyword, PageQuery q) {
        return commentAdmin.adminComments(status, verdict, hidden, keyword, q);
    }

    /** 兼容文章调用方；具体实现已归入report功能。 */
    @Transactional
    public void resolveReport(long admin, long id, Resolution request) {
        reportService.resolveReport(admin, id, request);
    }

    public void log(
            String type,
            long id,
            Integer revision,
            String actor,
            Long actorId,
            String decision,
            String note) {
        mapper.insertModerationLog(type, id, revision, actor, actorId, decision, note);
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
