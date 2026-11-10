package com.threadfit.glucopilot.community.ranking.mapper;

import com.threadfit.glucopilot.community.ranking.dto.HotArticleScore;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommunityHotRankingMapper {

    /** 方案 C 热榜分，按 score 降序取 TopN（全局发布候选，不含个性化可见性）。 */
    List<HotArticleScore> selectTopScores(@Param("limit") int limit);
}
