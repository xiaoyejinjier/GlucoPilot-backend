package com.threadfit.glucopilot.community.recommendation.entity;

import lombok.Data;

import java.time.LocalDate;

/** Aggregate of the current user's interactions with one tag on one day. */
@Data
public class ArticleTagSignal {
    private Long tagId;
    private LocalDate signalDate;
    private Double weight;
}
