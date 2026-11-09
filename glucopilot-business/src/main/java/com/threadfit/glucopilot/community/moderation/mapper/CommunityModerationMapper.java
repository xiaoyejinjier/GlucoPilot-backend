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
            "UPDATE community_comment SET ai_status='PENDING' WHERE ai_status='REVIEWING' AND"
                    + " ai_claimed_at<#{before} ")
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
