package com.threadfit.glucopilot.localization.service;


import static com.threadfit.glucopilot.localization.dto.LocalizationDtos.*;

import com.threadfit.glucopilot.ai.business.translation.service.EnglishTranslator;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.shared.service.CommunityService;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.localization.mapper.LocalizationMapper;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class LocalizationService {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(LocalizationService.class);
    // Corrected numeric-heading handling starts fresh jobs instead of reusing older failures.
    private static final String CACHE_VERSION = "en-v3:";
    /** 汉字探测：只用来判断'这段还有中文吗'，与具体实现无关，因此留在本层。 */
    private static final java.util.regex.Pattern HAN = java.util.regex.Pattern.compile("\\p{IsHan}");
    private static final Set<String> KINDS =
            Set.of("ARTICLE_PREVIEW", "ARTICLE_BODY", "PROFILE_NAME",
                    "CONSULTATION_PREVIEW", "CONSULTATION_DETAIL", "CONSULTATION_REPORT");
    private static final Set<String> LONG_KINDS =
            Set.of("ARTICLE_BODY", "CONSULTATION_DETAIL", "CONSULTATION_REPORT");
    /**
     * 队列满时「改约」的秒数：足够让 8 条一批的短文本跑完一轮，又不至于让用户等太久。
     * 取 5 秒是折中——比 0 大（避免同一请求循环反复认领、「认领-拒绝」空转），
     * 比 15 秒（原 FAILED 的 retry_after）小（排队和「真的翻不了」不是一回事）。
     */
    private static final long DEFER_SECONDS = 5;
    private final LocalizationMapper mapper;
    private final CommunityService community;
    private final ConsultationService consultations;
    private final EnglishTranslator translator;
    private final ObjectMapper json;
    private volatile boolean closing;
    private final ConcurrentMap<String, Job> activeJobs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "localization-lease");
                        t.setDaemon(true);
                        return t;
                    });
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(24),
                    r -> {
                        var t = new Thread(r, "english-localization");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    // 阅读正文不能排在整页的昵称/标题之后；保留有界队列，避免无限堆积模型调用。
    private final ThreadPoolExecutor bodyWorkers =
            new ThreadPoolExecutor(
                    1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12),
                    r -> {
                        var t = new Thread(r, "english-article-body");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public LocalizationService(
            LocalizationMapper mapper,
            CommunityService community,
            ConsultationService consultations,
            EnglishTranslator translator,
            ObjectMapper json) {
        this.mapper = mapper;
        this.community = community;
        this.consultations = consultations;
        this.translator = translator;
        this.json = json;
        heartbeat.scheduleWithFixedDelay(this::renewLeases, 20, 20, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() {
        closing = true;
        heartbeat.shutdownNow();
        for (var job : activeJobs.values()) {
            try {
                mapper.release(job.item(), job.hash(), job.lease());
            } catch (RuntimeException ignored) {
            }
        }
        workers.shutdownNow();
        bodyWorkers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
            bodyWorkers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void renewLeases() {
        for (var job : activeJobs.values()) {
            try {
                mapper.renew(
                        job.item(), job.hash(), job.lease(), LocalDateTime.now().plusSeconds(90));
            } catch (RuntimeException ignored) {
            }
        }
    }

    public List<Result> localize(long viewer, Request request) {
        if (request == null
                || request.items() == null
                || request.items().isEmpty()
                || request.items().size() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "每批最多20项");
        }
        for (Item item : request.items()) {
            validate(item);
        }
        if (mapper.countActiveUser(viewer) != 1) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        var result = new ArrayList<Result>();
        var unique = new HashSet<Item>();
        var jobs = new ArrayList<Job>();
        for (Item item : request.items()) {
            if (unique.add(item)) {
                result.add(item(viewer, item, Boolean.TRUE.equals(request.retry()), jobs));
            }
        }
        // Batch short names/previews. Long article bodies retain independent error boundaries.
        for (String kind : List.of("CONSULTATION_DETAIL", "CONSULTATION_REPORT", "ARTICLE_BODY",
                "CONSULTATION_PREVIEW", "ARTICLE_PREVIEW", "PROFILE_NAME")) {
            var matching = jobs.stream().filter(job -> job.item().kind().equals(kind)).toList();
            int batchSize = LONG_KINDS.contains(kind) ? 1 : 8;
            for (int start = 0; start < matching.size(); start += batchSize) {
                var batch =
                        List.copyOf(
                                matching.subList(
                                        start, Math.min(start + batchSize, matching.size())));
                batch.forEach(job -> activeJobs.put(job.lease(), job));
                try {
                    (LONG_KINDS.contains(kind) ? bodyWorkers : workers)
                            .execute(() -> translate(batch));
                } catch (RejectedExecutionException ex) {
                    // 2026-11-24：队列满时**不再直接判 FAILED**。
                    // 原实现把「这一秒排不上队」当成「这段内容翻不了」，于是客户端看到 FAILED 就放弃，
                    // 内容永远没有英文版；实际上只要等 5 秒、队列腾出位置就该重试。
                    // defer = 保持 PENDING 但把 lease_until 推到 5 秒后，这样这 5 秒内不会被
                    // 同一个请求循环反复认领（避免打空转），之后任何一次请求都能接手。
                    batch.forEach(
                            job -> {
                                activeJobs.remove(job.lease());
                                defer(job.item(), job.hash(), job.lease());
                            });
                    LOG.warn("英文翻译队列已满，{} 项改约 5 秒后重试 kind={}",
                            batch.size(), kind);
                }
            }
        }
        return result;
    }

    /** 队列满时的「改约」：保持 PENDING、清 token、把租约推到几秒后。 */
    private void defer(Item item, String hash, String lease) {
        try {
            mapper.defer(item, hash, lease, LocalDateTime.now().plusSeconds(DEFER_SECONDS));
        } catch (RuntimeException ignored) {
            /* 下一次请求仍能从过期租约接手，这里失败不影响正确性。 */
        }
    }

    /**
     * 每分钟扫一次「租约过期但 token 还挂着」的缓存行。
     *
     * <p>为什么需要它：翻译是**请求驱动**的（没有客户端请求就没有 Job），进程在
     * {@code claim} 之后崩溃会把这行留在 {@code PENDING + lease_token}。行本身是可被接手的
     * （{@code claim} 的条件允许 {@code lease_until < now}），但没人知道它卡着——所以这里定期清 token
     * 并打日志，让「积压」在日志里可见；真正重翻仍由下一次请求触发（服务端手上没有源文，
     * 不做无授权的后台重读，见文档）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void sweepExpiredLeases() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int cleared = mapper.clearExpiredLeases(now);
            if (cleared > 0) {
                LOG.info("英文翻译租约巡检：清理过期租约 {} 条（这些行仍为 PENDING，下一次请求会重新认领）",
                        cleared);
            }
        } catch (RuntimeException ex) {
            LOG.warn("英文翻译租约巡检失败（下次再试）{}", ex.getClass().getSimpleName());
        }
    }

    private record Job(Item item, String hash, String lease, Map<String, String> source) {}

    private Result item(long viewer, Item item, boolean retry, List<Job> jobs) {
        if (item == null || !KINDS.contains(item.kind()) || item.id() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无效的翻译对象");
        }
        Map<String, String> source;
        try {
            source = source(viewer, item);
        } catch (BizException ex) {
            if (ex.getErrorCode() == ErrorCode.NOT_FOUND
                    || (item.kind().startsWith("CONSULTATION_") && ex.getErrorCode() == ErrorCode.FORBIDDEN)
                    || ex.getErrorCode() == ErrorCode.BAD_REQUEST) {
                return new Result(item.kind(), item.id(), "UNAVAILABLE", "", Map.of(), Map.of());
            }
            throw ex;
        }
        String hash = hash(source);
        // English names and generated account nicknames don't require a remote AI request.
        var quick = direct(item, source);
        if (quick != null) {
            return new Result(item.kind(), item.id(), "READY", hash, source, quick);
        }
        var cached = cache(item, hash);
        if (cached == null) {
            try {
                mapper.insertPending(item, hash, translator.promptVersion());
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
            }
            cached = cache(item, hash);
        }
        if ("READY".equals(cached.status())) {
            return new Result(item.kind(), item.id(), "READY", hash, source, read(cached.json()));
        }
        String lease = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int claimed = mapper.claim(item, hash, lease, now.plusSeconds(90), now, retry);
        if (claimed == 1) {
            jobs.add(new Job(item, hash, lease, source));
            return new Result(item.kind(), item.id(), "PENDING", hash, source, Map.of());
        }
        return new Result(item.kind(), item.id(), cached.status(), hash, source, Map.of());
    }

    private static void validate(Item item) {
        if (item == null
                || !KINDS.contains(item.kind())
                || item.id() == null
                || !item.id()
                        .matches(
                                item.kind().equals("PROFILE_NAME")
                                        ? "[ub][1-9][0-9]{0,18}"
                                        : "[1-9][0-9]{0,18}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无效的翻译对象");
        }
        try {
            Long.parseLong(item.kind().equals("PROFILE_NAME") ? item.id().substring(1) : item.id());
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无效的翻译标识");
        }
    }

    private Map<String, String> source(long viewer, Item item) {
        if (item.kind().startsWith("CONSULTATION_")) {
            return consultationSource(viewer, item);
        }
        if (item.kind().equals("PROFILE_NAME")) {
            if (!item.id().matches("[ub][1-9][0-9]{0,18}")) {
                throw new BizException(ErrorCode.BAD_REQUEST, "无效的用户标识");
            }
            return Map.of(
                    "nickname",
                    Objects.toString(community.profile(viewer, item.id()).nickname(), ""));
        }
        long id;
        try {
            if (!item.id().matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            id = Long.parseLong(item.id());
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无效的文章标识");
        }
        community.requireReadable(viewer, id);
        // Read and authorize in the same SELECT too, so a concurrent privacy change cannot slip
        // through.
        var row = mapper.selectSource(viewer, id, item.kind().equals("ARTICLE_BODY"));
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文章不可查看");
        }
        var result = new LinkedHashMap<String, String>();
        if (item.kind().equals("ARTICLE_BODY")) {
            result.put("body", Objects.toString(row.getContentBody(), ""));
        } else {
            result.put("title", Objects.toString(row.getTitle(), ""));
            result.put("summary", Objects.toString(row.getSummaryText(), ""));
        }
        if (result.values().stream().mapToInt(String::length).sum() > 120000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文章过长，暂不支持英文阅读");
        }
        return result;
    }

    /** 从问诊业务已授权的视图取原文；先校验身份和付费权限，再读取任何共享译文缓存。 */
    private Map<String, String> consultationSource(long viewer, Item item) {
        var view = consultations.detail(viewer, Long.parseLong(item.id()));
        if (view == null) throw new BizException(ErrorCode.NOT_FOUND);
        var result = new LinkedHashMap<String, String>();
        switch (item.kind()) {
            case "CONSULTATION_PREVIEW" -> result.put("title", Objects.toString(view.title(), ""));
            case "CONSULTATION_DETAIL" -> {
                if (!view.detailsVisible()) throw new BizException(ErrorCode.NOT_FOUND);
                result.put("symptoms", Objects.toString(view.symptoms(), ""));
                result.put("glucoseNote", Objects.toString(view.glucoseNote(), ""));
                result.put("medicationNote", Objects.toString(view.medicationNote(), ""));
            }
            case "CONSULTATION_REPORT" -> {
                var report = view.report();
                if (!view.detailsVisible() || report == null || !"UNLOCKED".equals(report.access())) {
                    throw new BizException(ErrorCode.NOT_FOUND);
                }
                result.put("assessment", Objects.toString(report.assessment(), ""));
                result.put("medicationAdvice", Objects.toString(report.medicationAdvice(), ""));
                result.put("followUpAdvice", Objects.toString(report.followUpAdvice(), ""));
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST);
        }
        return result;
    }

    private Map<String, String> direct(Item item, Map<String, String> source) {
        if (source.values().stream()
                .noneMatch(value -> HAN.matcher(value).find())) {
            return source;
        }
        if (item.kind().equals("PROFILE_NAME")) {
            String value = source.get("nickname");
            if (value.matches("用户[_-][A-Za-z0-9_-]+")) {
                return Map.of("nickname", value.replaceFirst("^用户", "User"));
            }
        }
        return null;
    }

    private record Cached(String status, String json) {}

    private Cached cache(Item item, String hash) {
        var row = mapper.selectCache(item, hash);
        return row == null ? null : new Cached(row.getStatus(), row.getTranslatedJson());
    }

    private Map<String, String> read(String data) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : json.readTree(data).properties()) {
            result.put(entry.getKey(), entry.getValue().asString(""));
        }
        return result;
    }

    private String hash(Map<String, String> source) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (CACHE_VERSION
                                                            // 提示词版本进哈希：换提示词 = 换产出，旧缓存必须立刻失效，
                                                            // 否则「改了提示词却只对没被翻过的文章生效」这种问题极难发现
                                                            + translator.promptVersion()
                                                            + json.writeValueAsString(
                                                                    new TreeMap<>(source)))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void translate(List<Job> jobs) {
        try {
            if (closing || Thread.currentThread().isInterrupted()) {
                return;
            }
            var input = new LinkedHashMap<String, String>();
            if (jobs.size() == 1) {
                input.putAll(jobs.getFirst().source());
            } else {
                for (int i = 0; i < jobs.size(); i++) {
                    for (var entry : jobs.get(i).source().entrySet()) {
                        input.put("j" + i + "_" + entry.getKey(), entry.getValue());
                    }
                }
            }
            Map<String, String> output;
            try {
                output = translator.translate(jobs.getFirst().item().kind(), input);
                if (!output.keySet().equals(input.keySet())) {
                    throw new IllegalArgumentException("Missing translated fields");
                }
            } catch (RuntimeException ex) {
                // A malformed article/name must not fail every other item sharing its short-text
                // batch.
                // Split once only: single-job failures go through the normal FAILED/retry policy.
                if (jobs.size() == 1
                        || closing
                        || Thread.currentThread().isInterrupted()
                        || cancelled(ex)) {
                    throw ex;
                }
                LOG.warn(
                        "英文批量翻译失败，逐项隔离 kind={} items={} failureType={}",
                        jobs.getFirst().item().kind(),
                        jobs.size(),
                        ex.getClass().getSimpleName());
                for (var job : jobs) {
                    if (closing || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    translate(List.of(job));
                }
                return;
            }
            for (int i = 0; i < jobs.size(); i++) {
                var job = jobs.get(i);
                var translated = new LinkedHashMap<String, String>();
                for (String field : job.source().keySet()) {
                    translated.put(
                            field, output.get(jobs.size() == 1 ? field : "j" + i + "_" + field));
                }
                mapper.complete(
                        job.item(),
                        job.hash(),
                        job.lease(),
                        json.writeValueAsString(translated),
                        LocalDateTime.now());
            }
        } catch (Exception ex) {
            if (!closing) {
                LOG.warn(
                        "英文翻译未完成 kind={} items={} failureType={}",
                        jobs.getFirst().item().kind(),
                        jobs.size(),
                        ex.getClass().getSimpleName());
                jobs.forEach(job -> fail(job.item(), job.hash(), job.lease()));
            }
        } finally {
            jobs.forEach(job -> activeJobs.remove(job.lease()));
        }
    }

    private static boolean cancelled(Throwable error) {
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            if (error instanceof InterruptedException || error instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private void fail(Item item, String hash, String lease) {
        try {
            mapper.fail(
                    item, hash, lease, LocalDateTime.now().plusSeconds(15), LocalDateTime.now());
        } catch (RuntimeException ignored) {
            /* A later request can recover an expired lease. */
        }
    }
}
