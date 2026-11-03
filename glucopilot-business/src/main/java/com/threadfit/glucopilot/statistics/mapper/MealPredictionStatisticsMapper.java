package com.threadfit.glucopilot.statistics.mapper;

import com.threadfit.glucopilot.statistics.entity.DailyMealPredictionRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MealPredictionStatisticsMapper {
    @Select(
            """
            SELECT record_date,AVG(predict_suggest) predicted_avg,COUNT(*) prediction_count
            FROM meal_record
            WHERE user_id=#{userId} AND record_date BETWEEN #{from} AND #{to} AND is_deleted=0
              AND node_code IN ('breakfast','lunch','dinner')
              AND predict_suggest>0 AND predict_suggest<40
            GROUP BY record_date ORDER BY record_date
            """)
    List<DailyMealPredictionRow> selectDailySeries(
            @Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
