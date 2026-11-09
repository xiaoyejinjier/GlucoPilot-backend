package com.threadfit.glucopilot.community.recommendation.mapper;

import com.threadfit.glucopilot.community.recommendation.entity.ArticleTagSignal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 行为权重查询与评分片段见同包路径的CommunityRecommendationMapper.xml。 */
@Mapper
public interface CommunityRecommendationMapper {
    List<ArticleTagSignal> signals(
            @Param("viewer") long viewer,
            @Param("since") LocalDateTime since,
            @Param("asOf") LocalDateTime asOf);
}
