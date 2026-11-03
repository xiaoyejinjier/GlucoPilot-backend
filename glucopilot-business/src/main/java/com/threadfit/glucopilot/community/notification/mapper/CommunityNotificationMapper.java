package com.threadfit.glucopilot.community.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.notification.entity.CommunityNotification;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityNotificationMapper extends BaseMapper<CommunityNotification> {

    @Select(
            "SELECT COUNT(*) FROM user_account WHERE id=#{id} AND is_deleted=0 AND account_status=1"
                    + " ")
    Long countActiveUser(@Param("id") long id);

    @Select(
            "SELECT x.kind,COUNT(*) total,MAX(x.id) latest FROM community_notification x JOIN"
                + " user_account u ON u.id=x.actor_id AND u.is_deleted=0 AND u.account_status=1"
                + " LEFT JOIN cms_news n ON n.id=x.news_id LEFT JOIN community_article a ON"
                + " a.news_id=n.id LEFT JOIN community_comment c ON c.id=x.comment_id WHERE"
                + " x.recipient_id=#{viewer} AND (x.kind='FOLLOW' OR (n.is_deleted=0 AND"
                + " n.publish_status=1 AND n.publish_at<=CURRENT_TIMESTAMP AND"
                + " (n.community_managed=0 OR a.state='PUBLISHED'))) AND (x.kind<>'COMMENT' OR"
                + " (c.is_deleted=0 AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment root JOIN user_account ru ON ru.id=root.user_id AND"
                + " ru.is_deleted=0 AND ru.account_status=1 WHERE root.id=c.root_comment_id AND"
                + " root.is_deleted=0)))) AND (x.kind='FOLLOW' OR ( (NOT EXISTS(SELECT 1 FROM"
                + " community_article_access ac WHERE ac.news_id=n.id AND ac.visibility<>'PUBLIC')"
                + " OR (SELECT va.author_user_id FROM community_article va WHERE"
                + " va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1 FROM community_article_access ac"
                + " JOIN community_article_reader ar ON ar.news_id=ac.news_id WHERE ac.news_id=n.id"
                + " AND ac.visibility='FRIENDS' AND ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.target_key=CONCAT('u',(SELECT va.author_user_id FROM community_article va"
                + " WHERE va.news_id=n.id)) AND f.following=1) AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=(SELECT va.author_user_id FROM"
                + " community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1)))  AND NOT EXISTS(SELECT"
                + " 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                + " ah.user_id=#{viewer}) )) AND x.read_at IS NULL GROUP BY x.kind ")
    List<Map<String, Object>> selectUnreadCounts(@Param("viewer") long viewer);

    @Select(
            "SELECT COUNT(*) total,MAX(id) latest FROM community_report WHERE reporter_id=#{viewer}"
                    + " AND state IN ('RESOLVED','DISMISSED') AND resolved_at IS NOT NULL AND"
                    + " feedback_read_at IS NULL ")
    Map<String, Object> selectReportFeedbackCounts(@Param("viewer") long viewer);

    @Select(
            "SELECT COUNT(*) FROM community_notification x JOIN user_account u ON u.id=x.actor_id"
                + " AND u.is_deleted=0 AND u.account_status=1 LEFT JOIN cms_news n ON"
                + " n.id=x.news_id LEFT JOIN community_article a ON a.news_id=n.id LEFT JOIN"
                + " community_comment c ON c.id=x.comment_id WHERE x.recipient_id=#{viewer} AND"
                + " (x.kind='FOLLOW' OR (n.is_deleted=0 AND n.publish_status=1 AND"
                + " n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR"
                + " a.state='PUBLISHED'))) AND (x.kind<>'COMMENT' OR (c.is_deleted=0 AND"
                + " (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM community_comment root JOIN"
                + " user_account ru ON ru.id=root.user_id AND ru.is_deleted=0 AND"
                + " ru.account_status=1 WHERE root.id=c.root_comment_id AND root.is_deleted=0))))"
                + " AND (x.kind='FOLLOW' OR ( (NOT EXISTS(SELECT 1 FROM community_article_access ac"
                + " WHERE ac.news_id=n.id AND ac.visibility<>'PUBLIC') OR (SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1"
                + " FROM community_article_access ac JOIN community_article_reader ar ON"
                + " ar.news_id=ac.news_id WHERE ac.news_id=n.id AND ac.visibility='FRIENDS' AND"
                + " ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=#{viewer} AND f.target_key=CONCAT('u',(SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)) AND f.following=1) AND"
                + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=(SELECT"
                + " va.author_user_id FROM community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1)))  AND NOT EXISTS(SELECT"
                + " 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                + " ah.user_id=#{viewer}) )) AND x.kind=#{kind} ")
    Long countNotices(@Param("viewer") long viewer, @Param("kind") String kind);

    @Select(
            "SELECT x.*,n.title news_title,c.content comment_content FROM community_notification x"
                + " JOIN user_account u ON u.id=x.actor_id AND u.is_deleted=0 AND"
                + " u.account_status=1 LEFT JOIN cms_news n ON n.id=x.news_id LEFT JOIN"
                + " community_article a ON a.news_id=n.id LEFT JOIN community_comment c ON"
                + " c.id=x.comment_id WHERE x.recipient_id=#{viewer} AND (x.kind='FOLLOW' OR"
                + " (n.is_deleted=0 AND n.publish_status=1 AND n.publish_at<=CURRENT_TIMESTAMP AND"
                + " (n.community_managed=0 OR a.state='PUBLISHED'))) AND (x.kind<>'COMMENT' OR"
                + " (c.is_deleted=0 AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment root JOIN user_account ru ON ru.id=root.user_id AND"
                + " ru.is_deleted=0 AND ru.account_status=1 WHERE root.id=c.root_comment_id AND"
                + " root.is_deleted=0)))) AND (x.kind='FOLLOW' OR ( (NOT EXISTS(SELECT 1 FROM"
                + " community_article_access ac WHERE ac.news_id=n.id AND ac.visibility<>'PUBLIC')"
                + " OR (SELECT va.author_user_id FROM community_article va WHERE"
                + " va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1 FROM community_article_access ac"
                + " JOIN community_article_reader ar ON ar.news_id=ac.news_id WHERE ac.news_id=n.id"
                + " AND ac.visibility='FRIENDS' AND ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.target_key=CONCAT('u',(SELECT va.author_user_id FROM community_article va"
                + " WHERE va.news_id=n.id)) AND f.following=1) AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=(SELECT va.author_user_id FROM"
                + " community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1)))  AND NOT EXISTS(SELECT"
                + " 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                + " ah.user_id=#{viewer}) )) AND x.kind=#{kind} ORDER BY x.id DESC LIMIT #{limit}"
                + " OFFSET #{offset} ")
    List<Map<String, Object>> selectNotices(
            @Param("viewer") long viewer,
            @Param("kind") String kind,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select(
            "SELECT COUNT(*) FROM community_notification WHERE id=#{through} AND"
                    + " recipient_id=#{viewer} AND kind=#{kind} ")
    Long countOwnedNotice(
            @Param("through") long through,
            @Param("viewer") long viewer,
            @Param("kind") String kind);

    @Update(
            "UPDATE community_notification SET read_at=CURRENT_TIMESTAMP WHERE"
                + " recipient_id=#{viewer} AND kind=#{kind} AND id<=#{through} AND read_at IS NULL"
                + " ")
    int markReadThrough(
            @Param("viewer") long viewer,
            @Param("kind") String kind,
            @Param("through") long through);
}
