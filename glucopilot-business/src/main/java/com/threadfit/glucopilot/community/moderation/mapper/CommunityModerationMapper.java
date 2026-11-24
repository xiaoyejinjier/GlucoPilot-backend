package com.threadfit.glucopilot.community.moderation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.threadfit.glucopilot.community.moderation.entity.CommunityModerationHit;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityModerationMapper extends BaseMapper<CommunityModerationHit> {

    @Update(
            "UPDATE community_article SET"
                + " state='AI_ERROR',ai_verdict='ERROR',ai_reason='上次初审中断，请重新初审',updated_at=CURRENT_TIMESTAMP"
                + " WHERE state='AI_REVIEWING' AND updated_at<#{before} ")
    int expireArticleClaims(@Param("before") LocalDateTime before);

    @Select(
            "SELECT a.news_id,a.revision,n.title,n.content_body FROM community_article a JOIN"
                + " cms_news n ON n.id=a.news_id WHERE a.state='AI_PENDING' AND"
                + " n.community_managed=1 AND n.is_deleted=0 ORDER BY a.news_id LIMIT 1 FOR UPDATE"
                + " SKIP LOCKED ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> claimArticle();

    @Update(
            "UPDATE community_article SET state='AI_REVIEWING',updated_at=CURRENT_TIMESTAMP WHERE"
                    + " news_id=#{newsId} ")
    int markArticleReviewing(@Param("newsId") long newsId);

    @Update(
            "UPDATE community_comment SET ai_status='PENDING',ai_claimed_at=NULL WHERE"
                    + " ai_status='REVIEWING' AND ai_claimed_at<#{before} ")
    int expireCommentClaims(@Param("before") LocalDateTime before);

    @Select(
            "SELECT id,content FROM community_comment WHERE ai_status='PENDING' AND is_deleted=0"
                    + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> claimComment();

    @Update(
            "UPDATE community_comment SET ai_status='REVIEWING',ai_claimed_at=CURRENT_TIMESTAMP"
                    + " WHERE id=#{id} ")
    int markCommentReviewing(@Param("id") long id);

    @Update(
            "UPDATE community_article SET"
                + " state=#{state},ai_verdict=#{verdict},ai_reason=#{reason},ai_model=#{model},ai_category=#{category},ai_severity=#{severity},ai_confidence=#{confidence},ai_prompt_ver=#{promptVer},ai_checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                + " WHERE news_id=#{newsId} AND revision=#{revision} AND state='AI_REVIEWING' ")
    int finishArticle(
            @Param("state") String state,
            @Param("verdict") String verdict,
            @Param("reason") String reason,
            @Param("model") String model,
            @Param("category") String category,
            @Param("severity") Integer severity,
            @Param("confidence") Integer confidence,
            @Param("promptVer") String promptVer,
            @Param("newsId") long newsId,
            @Param("revision") Integer revision);

    @Update(
            "UPDATE community_comment SET"
                + " ai_status='DONE',ai_verdict=#{verdict},ai_reason=#{reason},ai_model=#{model},ai_category=#{category},ai_severity=#{severity},ai_confidence=#{confidence},ai_prompt_ver=#{promptVer},ai_checked_at=CURRENT_TIMESTAMP,is_hidden=CASE"
                + " WHEN #{rejected} THEN 1 ELSE is_hidden END WHERE id=#{id} AND"
                + " ai_status='REVIEWING' ")
    int finishComment(
            @Param("verdict") String verdict,
            @Param("reason") String reason,
            @Param("model") String model,
            @Param("category") String category,
            @Param("severity") Integer severity,
            @Param("confidence") Integer confidence,
            @Param("promptVer") String promptVer,
            @Param("rejected") boolean rejected,
            @Param("id") long id);

    @Select(
            "<script>SELECT is_hidden FROM community_comment WHERE id=#{commentId} <if"
                    + " test=\"requireExists\"> AND is_deleted=0</if> FOR UPDATE</script> ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Integer> lockCommentVisibility(
            @Param("commentId") long commentId, @Param("requireExists") boolean requireExists);

    @Update("UPDATE community_comment SET is_hidden=#{hidden} WHERE id=#{id} ")
    int setCommentHidden(@Param("hidden") int hidden, @Param("id") long id);

    @Insert(
            "<script>INSERT INTO"
                + " community_moderation_hit(target_type,target_id,revision,hit_source,category_code,severity,confidence,reason,model_code,prompt_ver)"
                + " VALUES <foreach collection='hits' item='hit'"
                + " separator=','>(#{hit.targetType},#{hit.targetId},#{hit.revision},'AI',#{hit.categoryCode},#{hit.severity},#{hit.confidence},#{hit.reason},#{hit.modelCode},#{hit.promptVer})</foreach></script>"
                + " ")
    int insertHits(@Param("hits") List<CommunityModerationHit> hits);

    /**
     * 按投递信里的 {@code news_id + revision} 精确抢一条文章（MQ 消费路径）。
     *
     * <p>与 {@link #claimArticle()} 的区别：那个是"随便给我一条"，这个是"只审这一条"。条件里带 {@code
     * revision} 很关键——消息在队列里排队期间作者可能已经改稿，旧版本号抢不到， 于是这条消息会被当成幂等重复 ack 掉，不会拿旧正文去审新版本。
     */
    @Select(
            "SELECT a.news_id,a.revision,n.title,n.content_body FROM community_article a JOIN"
                + " cms_news n ON n.id=a.news_id WHERE a.news_id=#{newsId} AND"
                + " a.revision=#{revision} AND a.state='AI_PENDING' AND n.community_managed=1 AND"
                + " n.is_deleted=0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> lockArticleForReview(
            @Param("newsId") long newsId, @Param("revision") Integer revision);

    /**
     * 数据库时钟。
     *
     * <p>僵尸回收与补扫的「多久算旧」必须和写入端同一个时钟：{@code ai_claimed_at}/{@code created_at} 由
     * {@code CURRENT_TIMESTAMP} 写入（数据库会话时区），而 JVM 的 {@code LocalDateTime.now()} 是进程时区。
     * 两者在容器里经常差 8 小时——拿 JVM 时间当阈值，会把刚抢到的任务判成「超时」，几秒内就被回收成待审，
     * 于是审核结论永远写不进去（{@code WHERE ai_status='REVIEWING'} 命中 0 行，前端一直看到 PENDING）。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    LocalDateTime selectDbNow();

    /** 按 id 精确抢一条评论（MQ 消费路径）；已被人抢走时返回空。 */
    @Select(
            "SELECT id,content FROM community_comment WHERE id=#{id} AND ai_status='PENDING' AND"
                    + " is_deleted=0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> lockCommentForReview(@Param("id") long id);

    /** 读文章正文（不加锁）：抢占与执行是两次独立调用，执行时行已经是 REVIEWING 状态。 */
    @Select(
            "SELECT n.title,n.content_body FROM community_article a JOIN cms_news n ON"
                    + " n.id=a.news_id WHERE a.news_id=#{newsId} AND a.revision=#{revision} ")
    List<Map<String, Object>> selectArticleContent(
            @Param("newsId") long newsId, @Param("revision") Integer revision);

    /** 读评论正文（不加锁）。 */
    @Select("SELECT content FROM community_comment WHERE id=#{id} AND is_deleted=0 ")
    List<Map<String, Object>> selectCommentContent(@Param("id") long id);

    /** 执行失败要重试时，把文章放回待审队列（条件更新：只回滚自己抢到的那一版）。 */
    @Update(
            "UPDATE community_article SET state='AI_PENDING',updated_at=CURRENT_TIMESTAMP WHERE"
                    + " news_id=#{newsId} AND revision=#{revision} AND state='AI_REVIEWING' ")
    int releaseArticleClaim(@Param("newsId") long newsId, @Param("revision") Integer revision);

    /** 执行失败要重试时，把评论放回待审队列。 */
    @Update(
            "UPDATE community_comment SET ai_status='PENDING',ai_claimed_at=NULL WHERE id=#{id} AND"
                    + " ai_status='REVIEWING' ")
    int releaseCommentClaim(@Param("id") long id);

    /**
     * 补扫用：超过一分钟还停在待审、且没人抢过的文章。
     *
     * <p>{@code updated_at} 而不是创建时间——文章被驳回改稿重投时会原地更新，用它才能覆盖"重投后没人审"。
     */
    @Select(
            "SELECT news_id,revision FROM community_article WHERE state='AI_PENDING' AND"
                    + " updated_at<#{before} ORDER BY updated_at,news_id LIMIT #{limit} ")
    List<Map<String, Object>> pendingArticleJobs(
            @Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 补扫用：超过一分钟还停在待审、且从没被抢过的评论（{@code ai_claimed_at IS NULL} 排除正在审的）。 */
    @Select(
            "SELECT id,user_id FROM community_comment WHERE ai_status='PENDING' AND ai_claimed_at IS NULL"
                    + " AND created_at<#{before} AND is_deleted=0 ORDER BY id LIMIT #{limit} ")
    List<Map<String, Object>> pendingCommentJobs(
            @Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 审计分页（管理端「AI 审计 → 内容审核」）：只读，按 id 倒序。
     *
     * <p>可按目标类型 / 类别 / 严重度过滤，都传 null 时就是「最近所有命中」。
     * 不加时间区间：表上已有 {@code idx_cmh_category(category_code, created_at)}，
     * 真要「查某天」时再加区间条件即可，现在不为猜测写 SQL。
     */
    @Select("<script>"
            + "SELECT * FROM community_moderation_hit WHERE 1 = 1"
            + "<if test='targetType != null'> AND target_type = #{targetType}</if>"
            + "<if test='categoryCode != null'> AND category_code = #{categoryCode}</if>"
            + "<if test='severity != null'> AND severity = #{severity}</if>"
            + " ORDER BY id DESC"
            + "</script>")
    IPage<CommunityModerationHit> selectHitAuditPage(IPage<CommunityModerationHit> page,
                                                    @Param("targetType") String targetType,
                                                    @Param("categoryCode") String categoryCode,
                                                    @Param("severity") Integer severity);
}
