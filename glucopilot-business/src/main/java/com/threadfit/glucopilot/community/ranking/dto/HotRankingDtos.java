package com.threadfit.glucopilot.community.ranking.dto;

import com.threadfit.glucopilot.community.article.dto.PublicationDtos;

import java.time.LocalDateTime;
import java.util.List;

public final class HotRankingDtos {
    private HotRankingDtos() {}

    public record HotArticlesResponse(
            List<PublicationDtos.Article> records, LocalDateTime generatedAt, int total) {}
}
