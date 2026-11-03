package com.threadfit.glucopilot.community.recommendation.mapper;

import com.threadfit.glucopilot.community.access.mapper.ArticleAccessSql;

import java.util.Map;

/**
 * 推荐SQL：查询用户行为，并为候选文章计算分数。 兴趣向量由 CommunityRecommendationService
 * 生成；这里负责匹配文章标签、评分和排序。
 * 所有输入使用绑定参数；文章查询先应用发布状态、可见性等过滤条件，再按推荐分排序。
 */
public final class CommunityRecommendationSql {
  private CommunityRecommendationSql() {
  }

  /**
   * 【1. 行为权重】读取指定时间范围内本人的有效行为，按标签ID和行为日期汇总。
   * 浏览记录权重1；当前点赞状态权重3；当前收藏状态权重5，同时点赞收藏则为8。 UNION
   * ALL合并两类来源；JOIN将文章行为映射到每个启用标签。 返回的weight尚未衰减；signal_date交给Service计算距离今天的天数。
   * 点赞/收藏使用互动行的gmt_modified作为时间，不是每次点击的历史事件。
   */
  public static String signals(Map<String, Object> args) {
    long viewer = ((Number) args.get("viewer")).longValue();
    return """
        SELECT t.id tag_id,s.signal_date,SUM(s.weight) weight
        FROM (
          SELECT v.news_id,v.view_date signal_date,1.0 weight
          FROM community_article_view v WHERE v.user_id=#{viewer}
            AND v.view_date >= CAST(#{since} AS DATE)
            AND v.view_date <= CAST(#{asOf} AS DATE) AND v.created_at <= #{asOf}
          UNION ALL
          SELECT u.news_id,CAST(u.gmt_modified AS DATE) signal_date,
            3.0*u.is_liked+5.0*u.is_collected weight
          FROM news_user_action u WHERE u.user_id=#{viewer} AND u.is_deleted=0
            AND (u.is_liked=1 OR u.is_collected=1)
            AND u.gmt_modified >= #{since} AND u.gmt_modified <= #{asOf}
        ) s
        JOIN cms_news n ON n.id=s.news_id
        JOIN community_article_tag atg ON atg.news_id=n.id
        JOIN community_tag t ON t.id=atg.tag_id AND t.enabled=1
        WHERE
        """
        + ArticleAccessSql.publishedSql("n")
        + " AND "
        + ArticleAccessSql.readableSql("n", viewer)
        + " GROUP BY t.id,s.signal_date";
  }

  /**
   * 【2. 兴趣匹配值，0～1】用户归一化向量与文章归一化标签向量的余弦相似度。
   * interest.tagId是用户兴趣标签，interest.weight是Service计算的归一化权重。
   * CASE找出文章标签对应的用户权重，SUM累加；文章未匹配的标签贡献0。 文章标签原始值为1，因此除以SQRT(文章启用标签数)就完成文章向量归一化。
   * 例：用户[0.6,0.8]，文章同时具有这两个标签，相似度=(0.6+0.8)/sqrt(2)。
   * 无用户兴趣或文章无启用标签时返回0；NULLIF避免除零，COALESCE处理空结果。
   */
  public static final String SIMILARITY = """
      <choose><when test="query.recommendation.tags.size() > 0">
        COALESCE((SELECT SUM(CASE rt.id
          <foreach collection="query.recommendation.tags" item="interest">
            WHEN #{interest.tagId} THEN #{interest.weight}
          </foreach>
          ELSE 0 END) / NULLIF(SQRT(COUNT(*)),0)
          FROM community_article_tag rat JOIN community_tag rt ON rt.id=rat.tag_id
          WHERE rat.news_id=n.id AND rt.enabled=1),0)
      </when><otherwise>0</otherwise></choose>
      """;

  /**
   * 【3. 热度值，0～1】使用所有用户对当前文章的互动，不是当前用户个人的行为。
   * H=点赞数+2×收藏数+0.1×浏览记录数；热度=min(1,ln(1+H)/ln(101))。
   * 对数使增长逐渐放缓；H达到100后封顶1。COALESCE/GREATEST处理空值和负计数。
   */
  public static final String POPULARITY = """
      LEAST(1.0,LN(1.0+GREATEST(COALESCE(n.like_count,0),0)
        +2.0*GREATEST(COALESCE(n.collect_count,0),0)
        +0.1*(SELECT COUNT(*) FROM community_article_view rv WHERE rv.news_id=n.id)) / LN(101.0))
      """;

  /**
   * 【4. 新鲜度值，0～1】1/(1+发布小时数/168)，168小时即7天。 刚发布为1，发布7天为0.5；GREATEST将负时间差按0处理。
   * 科学计数法1e0、168e0让MySQL使用DOUBLE运算，避免DECIMAL中间除法舍入。
   */
  public static final String FRESHNESS = "1e0 /"
      + " (1e0+GREATEST(TIMESTAMPDIFF(HOUR,n.publish_at,#{query.recommendation.asOf}),0)/168e0)";

  /**
   * 【5. 关注值，0或1】当前用户有效关注了文章作者则为1，否则为0，不要求互关。 follower_id是当前用户；following=1表示关注有效。
   * target_key兼容u+用户ID、b+博主ID，以及博主关联的用户身份。
   */
  public static final String FOLLOWED = """
      CASE WHEN EXISTS(SELECT 1 FROM community_follow rf
        WHERE rf.follower_id=#{query.viewer} AND rf.following=1
        AND (rf.target_key=CONCAT('u',a.author_user_id)
          OR rf.target_key=CONCAT('b',n.blogger_id)
          OR rf.target_key=CONCAT('u',(SELECT rb.user_id FROM community_blogger rb WHERE rb.id=n.blogger_id))))
      THEN 1 ELSE 0 END
      """;

  /**
   * 【6. 推荐总分，最高100】上面算的是匹配/热度/新鲜度/关注值，在此乘权重成为分数。 总分=60×相似度+15×热度+15×新鲜度+10×关注值。
   * 无兴趣向量的新用户兴趣分为0，仍可按其他三项获得排序。
   */
  public static final String SCORE = "60*("
      + SIMILARITY
      + ")+15*("
      + POPULARITY
      + ")+15*("
      + FRESHNESS
      + ")+10*("
      + FOLLOWED
      + ")";

  /**
   * 【7. 管理端评分明细】需要解释时额外查询四项原始值和总分，供推荐管理页面展示。
   * rec_similarity等四列尚未乘60/15/15/10；rec_score是加权后的总分。
   * 复用同一公式，确保管理端解释与小程序实际推荐一致。
   */
  public static final String EXPLANATION_COLUMNS = "<if test='query.explainRecommendation'>,("
      + SIMILARITY
      + ") rec_similarity,("
      + POPULARITY
      + ") rec_popularity,("
      + FRESHNESS
      + ") rec_freshness,("
      + FOLLOWED
      + ") rec_followed,("
      + SCORE
      + ") rec_score</if>";

  /**
   * 【8. 最终排序】总分降序，同分按发布时间、文章ID降序，文章查询随后分页。
   * 解释模式直接用rec_score别名，普通推荐展开SCORE；未启用推荐时按ID降序。
   */
  public static final String ORDER_BY = "<choose><when test='query.recommendation != null'> ORDER BY <choose><when"
      + " test='query.explainRecommendation'>rec_score</when><otherwise>("
      + SCORE
      + ")</otherwise></choose> DESC,n.publish_at DESC,n.id DESC"
      + " </when><otherwise>ORDER BY n.id DESC</otherwise></choose>";
}
