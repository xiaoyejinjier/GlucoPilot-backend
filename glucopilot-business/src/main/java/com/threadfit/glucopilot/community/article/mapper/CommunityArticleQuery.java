package com.threadfit.glucopilot.community.article.mapper;

import com.threadfit.glucopilot.community.recommendation.dto.CommunityRecommendation;

import lombok.Data;

import java.util.List;

/** Validated article filters, interpreted only by CommunityPublicationMapper. */
@Data
public class CommunityArticleQuery {
    private Long viewer;
    private boolean published;
    private boolean readable;
    private boolean following;
    private String tag;
    private String keyword;
    private Long authorUser;
    private Long blogger;
    private Long mine;
    private String state;
    private String source;
    private boolean adminSearch;
    private CommunityRecommendation recommendation;
    private boolean explainRecommendation;
    /** 热榜等按 id 批量灌装；与 readable/published 组合使用。 */
    private List<Long> newsIds;
}
