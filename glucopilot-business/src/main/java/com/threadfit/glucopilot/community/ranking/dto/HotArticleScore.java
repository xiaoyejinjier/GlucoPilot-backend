package com.threadfit.glucopilot.community.ranking.dto;

/** MyBatis 映射用热榜分；与 HotRankingDtos.HotArticleScore 同义。 */
public class HotArticleScore {
    private long newsId;
    private double score;

    public HotArticleScore() {}

    public HotArticleScore(long newsId, double score) {
        this.newsId = newsId;
        this.score = score;
    }

    public long getNewsId() {
        return newsId;
    }

    public void setNewsId(long newsId) {
        this.newsId = newsId;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public long newsId() {
        return newsId;
    }

    public double score() {
        return score;
    }
}
