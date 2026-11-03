package com.threadfit.glucopilot.community.recommendation.mapper;

import com.threadfit.glucopilot.community.recommendation.entity.ArticleTagSignal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityRecommendationMapper {
    @SelectProvider(type = CommunityRecommendationSql.class, method = "signals")
    List<ArticleTagSignal> signals(
            @Param("viewer") long viewer,
            @Param("since") LocalDateTime since,
            @Param("asOf") LocalDateTime asOf);
}
