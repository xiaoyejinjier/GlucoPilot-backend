package com.threadfit.glucopilot.community.access.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.access.entity.CommunityArticleAccess;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface ArticleAccessMapper extends BaseMapper<CommunityArticleAccess> {

    @Select(
            "SELECT COUNT(*) FROM user_account WHERE id=#{id} AND is_deleted=0 AND account_status=1"
                    + " ")
    Long countActiveUser(@Param("id") long id);

    @Select(
            "<script>SELECT COUNT(*) FROM cms_news n WHERE n.id=#{id} AND n.is_deleted=0 AND"
                + " n.publish_status=1 AND n.publish_at&lt;=CURRENT_TIMESTAMP AND"
                + " (n.community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa LEFT JOIN"
                + " user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND"
                + " au.account_status=1 WHERE pa.news_id=n.id AND pa.state='PUBLISHED' AND (au.id"
                + " IS NOT NULL OR (pa.author_user_id IS NULL AND EXISTS(SELECT 1 FROM"
                + " community_blogger eb WHERE eb.id=n.blogger_id AND eb.enabled=1 AND eb.user_id"
                + " IS NULL))))) AND  (NOT EXISTS(SELECT 1 FROM community_article_access ac WHERE"
                + " ac.news_id=n.id AND ac.visibility&lt;&gt;'PUBLIC') OR (SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1"
                + " FROM community_article_access ac JOIN community_article_reader ar ON"
                + " ar.news_id=ac.news_id WHERE ac.news_id=n.id AND ac.visibility='FRIENDS' AND"
                + " ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=#{viewer} AND f.target_key=CONCAT('u',(SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)) AND f.following=1) AND"
                + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=(SELECT"
                + " va.author_user_id FROM community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1))) <if test='preferences'>"
                + " AND NOT EXISTS(SELECT 1 FROM community_article_hidden ah WHERE ah.news_id=n.id"
                + " AND ah.user_id=#{viewer})</if></script> ")
    Long countReadableArticle(
            @Param("id") long id,
            @Param("viewer") Long viewer,
            @Param("preferences") boolean preferences);

    @Select("SELECT COUNT(*) FROM community_article_view WHERE news_id=#{id} ")
    Long countViews(@Param("id") long id);

    @Select("SELECT visibility,revision FROM community_article_access WHERE news_id=#{id} ")
    List<Map<String, Object>> selectPolicy(@Param("id") long id);

    @Select("SELECT user_id FROM community_article_reader WHERE news_id=#{id} ORDER BY user_id ")
    List<Long> selectReaders(@Param("id") long id);

    @Select(
            "SELECT COUNT(*) FROM user_account u WHERE u.id=#{peer} AND u.is_deleted=0 AND"
                + " u.account_status=1 AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=#{author} AND f.target_key=CONCAT('u',u.id) AND f.following=1)"
                + " AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=u.id AND"
                + " f.target_key=CONCAT('u',#{author}) AND f.following=1) ")
    Long countMutualFriend(@Param("peer") long peer, @Param("author") long author);

    @Select(
            "SELECT COUNT(*) FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND"
                + " p.is_deleted=0  WHERE u.is_deleted=0 AND u.account_status=1 AND u.id<>#{viewer}"
                + " AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.target_key=CONCAT('u',u.id) AND f.following=1) AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=u.id AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1) AND"
                + " LOCATE(#{keyword},COALESCE(p.nickname,''))>0 ")
    Long countFriends(@Param("viewer") long viewer, @Param("keyword") String keyword);

    @Select(
            "SELECT u.id,p.nickname,p.avatar_url,p.bio FROM user_account u LEFT JOIN user_profile p"
                + " ON p.user_id=u.id AND p.is_deleted=0  WHERE u.is_deleted=0 AND"
                + " u.account_status=1 AND u.id<>#{viewer} AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.target_key=CONCAT('u',u.id) AND f.following=1) AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=u.id AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1) AND"
                + " LOCATE(#{keyword},COALESCE(p.nickname,''))>0 ORDER BY u.id DESC LIMIT #{limit}"
                + " OFFSET #{offset} ")
    List<Map<String, Object>> selectFriends(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Insert(
            "INSERT INTO community_article_access(news_id,visibility,revision)"
                + " VALUES(#{id},#{visibility},1) ON DUPLICATE KEY UPDATE"
                + " visibility=#{updatedVisibility},revision=revision+1,updated_at=CURRENT_TIMESTAMP"
                + " ")
    int upsertPolicy(
            @Param("id") long id,
            @Param("visibility") String visibility,
            @Param("updatedVisibility") String updatedVisibility);

    @Delete("DELETE FROM community_article_reader WHERE news_id=#{id} ")
    int deleteReaders(@Param("id") long id);

    @Insert("INSERT INTO community_article_reader(news_id,user_id) VALUES(#{id},#{reader}) ")
    int insertReader(@Param("id") long id, @Param("reader") long reader);

    @Select(
            "SELECT a.author_user_id FROM community_article a JOIN cms_news n ON n.id=a.news_id"
                    + " WHERE a.news_id=#{id} AND n.is_deleted=0 FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockAuthor(@Param("id") long id);

    @Select(
            "SELECT COUNT(*) FROM community_article WHERE news_id=#{id} AND"
                    + " author_user_id=#{viewer} ")
    Long countOwnArticle(@Param("id") long id, @Param("viewer") long viewer);

    @Insert(
            "INSERT IGNORE INTO community_article_view(news_id,user_id,view_date)"
                    + " VALUES(#{id},#{viewer},CURRENT_DATE) ")
    int insertDailyView(@Param("id") long id, @Param("viewer") long viewer);

    @Insert("INSERT IGNORE INTO community_article_hidden(user_id,news_id) VALUES(#{viewer},#{id}) ")
    int insertHiddenArticle(@Param("viewer") long viewer, @Param("id") long id);

    @Delete("DELETE FROM community_article_hidden WHERE user_id=#{viewer} AND news_id=#{id} ")
    int deleteHiddenArticle(@Param("viewer") long viewer, @Param("id") long id);

    @Select(
            "SELECT COUNT(*) FROM community_article_hidden WHERE user_id=#{viewer} AND"
                    + " news_id=#{id} ")
    Long countHiddenArticle(@Param("viewer") long viewer, @Param("id") long id);

    @Select(
            "SELECT COUNT(*) FROM community_article WHERE news_id=#{id} AND"
                    + " author_user_id=#{viewer} ")
    Long countOwnArticleForHiding(@Param("id") long id, @Param("viewer") long viewer);
}
