package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 社区审核队列：用业务表自己当队列（不引 MQ）。
 *
 * <h2>抢占式消费</h2>
 * {@code SELECT … FOR UPDATE SKIP LOCKED} 让多实例各自取到不同的行，不会重复审核同一条内容；
 * 抢占后把状态置成"审核中"，再由 {@link #finish} 用<b>状态 + 版本号</b>做条件更新，
 * 因此"审核结果写回"只会发生一次（迟到的重复结果影响 0 行）。
 *
 * <h2>僵尸任务</h2>
 * 进程在"审核中"阶段崩溃时，行会一直停在 AI_REVIEWING。{@link #claim()} 每次先把
 * 超过 3 分钟没动静的行打回 AI_ERROR（可被人工重试），避免永久卡住。
 *
 * <h2>为什么审核结论与命中的写入必须在同一事务</h2>
 * 结论（state/ai_*）与证据（{@code community_moderation_hit}）是同一件事的两面：
 * 只写结论会让运营无法解释"为什么判它违规"，只写证据则页面看不到结论。
 */
@Service
public class CommunityModerationQueue {

    /** "审核中"超过这个时长视为僵尸任务（进程崩溃/超时未回收）。 */
    static final int STALE_MINUTES = 3;

    /** 审核目标的两种类型，与 {@code community_moderation_hit.target_type} / 日志表口径一致。 */
    static final String ARTICLE = "ARTICLE";
    static final String COMMENT = "COMMENT";

    /**
     * 一条待审内容。
     *
     * @param targetType {@link #ARTICLE} 或 {@link #COMMENT}
     * @param id         业务主键（文章是 news_id，评论是 comment_id）
     * @param revision   文章版本号；评论为 null（评论没有版本概念，编辑即新内容）
     * @param title      文章标题；评论为 null
     * @param body       正文
     * @param tags       文章标签；评论为空列表
     */
    public record Job(String targetType, long id, Integer revision, String title, String body,
                      List<String> tags) {

        /** 文章 Job 的便捷构造（保持调用方写法不变）。 */
        public Job(long id, int revision, String title, String body, List<String> tags) {
            this(ARTICLE, id, revision, title, body, tags);
        }

        public boolean isComment() {
            return COMMENT.equals(targetType);
        }
    }

    private final JdbcTemplate jdbc;

    private final TransactionTemplate tx;

    private final CommunityPublicationService publication;

    public CommunityModerationQueue(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                    CommunityPublicationService publication) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.publication = publication;
    }

    /**
     * 取一条待审内容并置为"审核中"；两类都为空时返回 null。
     * <p>
     * <b>文章优先</b>：文章的发布被初审卡着（用户看不到自己的文章），而评论是先发后审（已经可见），
     * 所以同等条件下先结文章，用户感知的等待时间最短。
     */
    public Job claim() {
        Job article = claimArticle();
        return article != null ? article : claimComment();
    }

    /** 取一条待审文章并置为"审核中"；没有待审内容时返回 null。 */
    public Job claimArticle() {
        return tx.execute(status -> {
            jdbc.update("UPDATE community_article SET state='AI_ERROR',ai_verdict='ERROR',"
                            + "ai_reason='上次初审中断，请重新初审',updated_at=CURRENT_TIMESTAMP "
                            + "WHERE state='AI_REVIEWING' AND updated_at<?",
                    LocalDateTime.now().minusMinutes(STALE_MINUTES));
            var rows = jdbc.query("SELECT a.news_id,a.revision,n.title,n.content_body FROM community_article a "
                            + "JOIN cms_news n ON n.id=a.news_id "
                            + "WHERE a.state='AI_PENDING' AND n.community_managed=1 AND n.is_deleted=0 "
                            + "ORDER BY a.news_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (rs, n) -> new Job(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), List.of()));
            if (rows.isEmpty()) {
                return null;
            }
            Job row = rows.getFirst();
            jdbc.update("UPDATE community_article SET state='AI_REVIEWING',updated_at=CURRENT_TIMESTAMP WHERE news_id=?",
                    row.id());
            return new Job(row.id(), row.revision(), row.title(), row.body(),
                    publication.tags(row.id()).stream().map(PublicationDtos.Tag::name).toList());
        });
    }

    /**
     * 取一条待审评论并置为"审核中"；没有待审内容时返回 null。
     * <p>
     * 僵尸任务的处理与文章不同：评论被回收成 {@code PENDING} <b>重新排队</b>，
     * 而不是直接判错——评论没有"人工重试"按钮，判错就意味着一批评论永远停在未审状态。
     */
    public Job claimComment() {
        return tx.execute(status -> {
            jdbc.update("UPDATE community_comment SET ai_status='PENDING' "
                            + "WHERE ai_status='REVIEWING' AND ai_claimed_at<?",
                    LocalDateTime.now().minusMinutes(STALE_MINUTES));
            var rows = jdbc.query("SELECT id,content FROM community_comment "
                            + "WHERE ai_status='PENDING' AND is_deleted=0 "
                            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (rs, n) -> new Job(COMMENT, rs.getLong(1), null, null, rs.getString(2), List.of()));
            if (rows.isEmpty()) {
                return null;
            }
            Job row = rows.getFirst();
            jdbc.update("UPDATE community_comment SET ai_status='REVIEWING',ai_claimed_at=CURRENT_TIMESTAMP "
                    + "WHERE id=?", row.id());
            return row;
        });
    }

    /**
     * 写回一篇的审核结论（含证据与日志），同事务完成。
     * <p>
     * 状态映射与 V31 完全一致，前端 {@code CommunityReviewView} 的映射表因此不用改：
     * PASS → PENDING_ADMIN（进人工终审）、REJECT → AI_REJECTED、其余 → AI_ERROR。
     *
     * @param verdict   判定结果（含类别/严重度/置信度）
     * @param model     本轮模型编码（可为空串）
     * @param promptVer 提示词版本标签，如 {@code moderation@v1}；用于事后复现"当时用的是哪一版"
     * @return true = 本次真的写回（false 表示已被并发处理或版本已变，属正常竞争）
     */
    public boolean finish(Job job, ModerationVerdict verdict, String model, String promptVer) {
        return job.isComment()
                ? finishComment(job, verdict, model, promptVer)
                : finishArticle(job, verdict, model, promptVer);
    }

    private boolean finishArticle(Job job, ModerationVerdict verdict, String model, String promptVer) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String nextState = switch (verdict.verdict()) {
                case ModerationVerdict.PASS -> "PENDING_ADMIN";
                case ModerationVerdict.REJECT -> "AI_REJECTED";
                default -> "AI_ERROR";
            };
            String reason = cut(verdict.reason(), 1000);
            int changed = jdbc.update("UPDATE community_article SET state=?,ai_verdict=?,ai_reason=?,ai_model=?,"
                            + "ai_category=?,ai_severity=?,ai_confidence=?,ai_prompt_ver=?,"
                            + "ai_checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE news_id=? AND revision=? AND state='AI_REVIEWING'",
                    nextState, verdict.verdict(), reason, cut(model, 128),
                    verdict.categoryCode(),
                    verdict.isReject() ? verdict.severity() : null,
                    verdict.isReject() ? verdict.confidence() : null,
                    cut(promptVer, 32),
                    job.id(), job.revision());
            if (changed != 1) {
                return false;
            }
            insertHits(ARTICLE, job.id(), job.revision(), verdict, model, promptVer, reason);
            publication.log(ARTICLE, job.id(), job.revision(), "AI", null, verdict.verdict(), reason);
            return true;
        }));
    }

    /**
     * 写回一条评论的审核结论。
     * <p>
     * <b>先发后审</b>：命中 REJECT 立即 {@code is_hidden=1}（从公开列表消失），
     * 由管理端的恢复接口兜底误杀；{@code ERROR} <b>不隐藏</b>——模型故障时把所有新评论藏起来
     * 等于社区停摆，但 {@code ai_verdict=ERROR} 也不等于通过，运营可按它筛出来复查。
     * <p>
     * {@code is_hidden} 的写法是"只升不降"（REJECT 置 1，其余保持原值）：管理员可能在评论还在排队时
     * 就先手动隐藏了它，此时 AI 随后给出的 PASS/ERROR 不该把人工决定悄悄推翻——机器能加严，
     * 不能替人放宽，放宽只能由人在管理端做。
     */
    private boolean finishComment(Job job, ModerationVerdict verdict, String model, String promptVer) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String reason = cut(verdict.reason(), 1000);
            int changed = jdbc.update("UPDATE community_comment SET ai_status='DONE',ai_verdict=?,ai_reason=?,"
                            + "ai_model=?,ai_category=?,ai_severity=?,ai_confidence=?,ai_prompt_ver=?,"
                            + "ai_checked_at=CURRENT_TIMESTAMP,is_hidden=CASE WHEN ? THEN 1 ELSE is_hidden END "
                            + "WHERE id=? AND ai_status='REVIEWING'",
                    verdict.verdict(), reason, cut(model, 128),
                    verdict.categoryCode(),
                    verdict.isReject() ? verdict.severity() : null,
                    verdict.isReject() ? verdict.confidence() : null,
                    cut(promptVer, 32),
                    verdict.isReject(),
                    job.id());
            if (changed != 1) {
                return false;
            }
            insertHits(COMMENT, job.id(), null, verdict, model, promptVer, reason);
            publication.log(COMMENT, job.id(), null, "AI", null, verdict.verdict(), reason);
            return true;
        }));
    }

    /**
     * 管理端主动隐藏 / 恢复一条评论（人工兜底）。
     * <p>
     * 两个方向都只动 {@code is_hidden} 一列，<b>不改写 {@code ai_verdict}</b>：
     * "机器判它违规"是发生过的事实，删掉它就再也解释不清"为什么这条被隐藏过"。
     * 反过来，管理员把一条 AI 判通过的正常评论藏起来时，{@code ai_verdict} 仍是 PASS——
     * 那正是"人工否决了机器结论"，两边都留着才解释得清。
     * 每次真实的状态变化写一条 {@code community_moderation_log}（ADMIN + HIDE/RESTORE），可追溯。
     *
     * @param hidden true 隐藏，false 恢复显示
     * @return false 表示这条评论已经处于目标状态（幂等，不报错）
     * @throws BizException 评论不存在或已被逻辑删除
     */
    public boolean setCommentHidden(long opsId, long commentId, boolean hidden) {
        return applyVisibility(opsId, commentId, hidden, true);
    }

    /**
     * 管理端恢复被机器隐藏的评论（误杀兜底，历史入口，等价于 {@code setCommentHidden(…, false)}）。
     * <p>
     * 与 {@link #setCommentHidden} 的差别只在"评论不存在"时的语义：这里保持静默返回 false，
     * 因为恢复接口从上线起就声明了幂等（运营重复点击、列表数据过期都不该报错）。
     *
     * @return false 表示这条评论不存在或当前并未隐藏（幂等，不报错）
     */
    public boolean restoreComment(long opsId, long commentId) {
        return applyVisibility(opsId, commentId, false, false);
    }

    /**
     * @param requireExists true 时评论不存在/已删除抛 404（管理端主动操作需要明确失败），
     *                      false 时静默返回 false（历史恢复接口的幂等语义）
     */
    private boolean applyVisibility(long opsId, long commentId, boolean hidden, boolean requireExists) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            // 行锁 + 读当前值：判断"是否真的需要变"，避免重复点击写出重复日志。
            List<Integer> current = jdbc.queryForList(
                    "SELECT is_hidden FROM community_comment WHERE id=?" + (requireExists ? " AND is_deleted=0" : "")
                            + " FOR UPDATE", Integer.class, commentId);
            if (current.isEmpty()) {
                if (requireExists) {
                    throw new BizException(ErrorCode.NOT_FOUND, "评论不存在或已删除");
                }
                return false;
            }
            if ((current.getFirst() == 1) == hidden) {
                return false;
            }
            jdbc.update("UPDATE community_comment SET is_hidden=? WHERE id=?", hidden ? 1 : 0, commentId);
            publication.log(COMMENT, commentId, null, "ADMIN", opsId,
                    hidden ? "HIDE" : "RESTORE",
                    hidden ? "管理员主动隐藏评论" : "管理员恢复被 AI 隐藏的评论");
            return true;
        }));
    }


    /**
     * 写入命中明细（只追加；只存元数据，不存原文）。
     * <p>
     * 当前提示词不要求模型回吐原句，因此 {@code start_offset}/{@code snippet_hash} 留空；
     * 解析器已支持 {@code evidence} 行，将来在提示词里放开即可自动补上偏移。
     */
    void insertHits(String targetType, long targetId, Integer revision, ModerationVerdict verdict,
                    String model, String promptVer, String reason) {
        if (!verdict.isReject() || verdict.hits().isEmpty()) {
            return;
        }
        List<Object[]> batch = verdict.hits().stream()
                .map(hit -> new Object[]{
                        targetType, targetId, revision, hit.category().code(), hit.severity(),
                        hit.confidence(), cut(reason, 500), cut(model, 128), cut(promptVer, 32)})
                .toList();
        jdbc.batchUpdate("INSERT INTO community_moderation_hit"
                + "(target_type,target_id,revision,hit_source,category_code,severity,confidence,reason,model_code,prompt_ver) "
                + "VALUES(?,?,?,'AI',?,?,?,?,?,?)", batch);
    }

    /** 按列宽截断；null 安全（DB 列多为 NOT NULL DEFAULT ''）。 */
    static String cut(String text, int max) {
        return text == null ? "" : text.substring(0, Math.min(max, text.length()));
    }
}
