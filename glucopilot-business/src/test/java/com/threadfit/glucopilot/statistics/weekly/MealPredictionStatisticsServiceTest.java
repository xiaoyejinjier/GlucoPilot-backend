package com.threadfit.glucopilot.statistics.weekly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MealPredictionStatisticsServiceTest {
    private JdbcTemplate jdbc;
    private MealPredictionStatisticsService service;
    private final LocalDate day = LocalDate.of(2026, 10, 26);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:meal_prediction_"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE meal_record (user_id BIGINT, record_date DATE, node_code VARCHAR(32),
                  predict_now DECIMAL(4,1), predict_suggest DECIMAL(4,1), is_deleted TINYINT)
                """);
        service = new MealPredictionStatisticsService(jdbc);
    }

    @Test
    void averagesOnlyRecommendedMealsWithinCurrentUserAndInclusiveDateRange() {
        insert(1, day, "breakfast", "20.0", "6.0", 0);
        insert(1, day, "lunch", "21.0", "7.0", 0);
        insert(1, day, "dinner", "22.0", "9.0", 0);
        insert(2, day, "breakfast", "1.0", "1.0", 0);
        insert(1, day, "breakfast", "30.0", "30.0", 1);
        insert(1, day, "after_breakfast", "30.0", "30.0", 0);
        insert(1, day.minusDays(1), "breakfast", "30.0", "30.0", 0);
        insert(1, day.plusDays(7), "breakfast", "30.0", "30.0", 0);
        insert(1, day.plusDays(6), "dinner", "20.0", "8.0", 0);
        var values = service.dailySeries(1L, day, day.plusDays(6));
        assertEquals(2, values.size());
        assertEquals(new BigDecimal("7.3"), values.get(day).average());
        assertEquals(3, values.get(day).count());
        assertEquals(new BigDecimal("8.0"), values.get(day.plusDays(6)).average());
        assertEquals(1, values.get(day.plusDays(6)).count());
        assertFalse(values.containsKey(day.plusDays(1)));
    }

    @Test
    void partialMealsAreNotZeroFilledAndMissingSuggestNeverFallsBackToNow() {
        insert(1, day, "breakfast", "20.0", "6.5", 0);
        insert(1, day, "lunch", "21.0", null, 0);
        insert(1, day, "dinner", "22.0", "8.0", 0);
        var value = service.dailySeries(1L, day, day).get(day);
        assertEquals(new BigDecimal("7.3"), value.average());
        assertEquals(2, value.count());
    }

    @Test
    void zeroNegativeOutOfRangeNullAndDeletedValuesDoNotMakeAPrediction() {
        insert(1, day, "breakfast", "7.0", null, 0);
        insert(1, day, "lunch", "7.0", "0.0", 0);
        insert(1, day, "dinner", "7.0", "40.0", 0);
        insert(1, day.plusDays(1), "breakfast", "7.0", "-1.0", 0);
        insert(1, day.plusDays(1), "lunch", "7.0", "8.0", 1);
        assertTrue(service.dailySeries(1L, day, day.plusDays(1)).isEmpty());
    }

    private void insert(long userId, LocalDate date, String node, String now, String suggested, int deleted) {
        jdbc.update("INSERT INTO meal_record VALUES (?, ?, ?, ?, ?, ?)", userId, date, node, now, suggested, deleted);
    }
}
