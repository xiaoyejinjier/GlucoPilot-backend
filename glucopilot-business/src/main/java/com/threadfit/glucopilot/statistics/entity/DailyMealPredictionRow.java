package com.threadfit.glucopilot.statistics.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 已保存的推荐吃法预测结果的按日汇总，不参与实测血糖统计。 */
@Data
public class DailyMealPredictionRow {
    private LocalDate recordDate;
    private BigDecimal predictedAvg;
    private Integer predictionCount;
}
