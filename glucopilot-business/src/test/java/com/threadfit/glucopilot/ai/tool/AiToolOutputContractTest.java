package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.service.FoodService;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import com.threadfit.glucopilot.statistics.daily.DailyStatisticsService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import com.threadfit.glucopilot.statistics.monthly.MonthlyStatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 各工具"返回什么、不返回什么"的契约测试。
 * <p>
 * 这组用例盯的是**看不出来但很重要**的东西：
 * <ol>
 *   <li><b>合规裁剪</b>：健康档案工具绝不能回传 {@code medication}（用药）——
 *       模型拿到用药信息就可能在回答里顺着聊剂量，而这是本项目的医疗红线。
 *       这类缺陷在人工试用时几乎发现不了（模型"看起来没提用药"），只有断言能守住；</li>
 *   <li><b>无用字段</b>：{@code mediaId}/{@code mediaUrl}/{@code id} 等对回答无用、只花 token；</li>
 *   <li><b>易被误解的字段</b>：{@code SlotStat.pct} 是"端上进度条比例"而非达标率，必须剔除；</li>
 *   <li><b>缺省与边界</b>：省略时间参数要落到"当前"、越界参数要给出可读错误。</li>
 * </ol>
 */
class AiToolOutputContractTest {

    private static final Long USER_ID = 7L;
    private static final ToolContext CONTEXT = new ToolContext(Map.of(AiToolContext.USER_ID, USER_ID));

    private static JsonUtils json() {
        return new JsonUtils(new ObjectMapper());
    }

    // ---------- get_health_profile：合规裁剪 ----------

    @Test
    void 健康档案工具绝不应回传用药信息() {
        HealthProfileService service = mock(HealthProfileService.class);
        HealthDtos.HealthView view = HealthDtos.HealthView.builder()
                .id("123")
                .age(52)
                .diabetesTypeText("2型糖尿病")
                .medication("二甲双胍 0.5g bid")     // 库里确实有这个字段
                .targetFasting(new BigDecimal("6.0"))
                .build();
        when(service.healthViewOf(USER_ID)).thenReturn(view);

        String json = new HealthProfileTools(service, json()).getHealthProfile(CONTEXT);

        // 最关键的一条：用药信息不进模型上下文
        assertThat(json).doesNotContain("medication");
        assertThat(json).doesNotContain("二甲双胍");
        // 内部主键也没必要给
        assertThat(json).doesNotContain("\"id\"");
        // 但该给的要给到，否则就是"什么都没返回"式的假通过
        assertThat(json).contains("2型糖尿病").contains("\"age\":52").contains("targetFasting");
    }

    @Test
    void 健康档案为空时应明确告知而不是让模型猜() {
        HealthProfileService service = mock(HealthProfileService.class);
        when(service.healthViewOf(USER_ID)).thenReturn(HealthDtos.HealthView.builder().build());

        String json = new HealthProfileTools(service, json()).getHealthProfile(CONTEXT);

        assertThat(json).contains("\"hasProfile\":false");
        assertThat(json).contains("hint");
    }

    // ---------- get_daily_checkin / get_month_stats ----------

    @Test
    void 打卡工具应只回统计口径的字段() {
        DailyStatisticsService service = mock(DailyStatisticsService.class);
        StatisticsDtos.CheckIn checkIn = StatisticsDtos.CheckIn.builder()
                .todayCount(4)
                .todayAvg(new BigDecimal("7.1"))
                .totalNodes(6)
                .streakDays(9)
                .monthRecordDays(18)
                .build();
        when(service.checkIn(eq(USER_ID), any(LocalDate.class))).thenReturn(checkIn);

        String json = new StatisticsTools(service, mock(MonthlyStatisticsService.class), json())
                .getDailyCheckin(CONTEXT);

        assertThat(json).contains("\"todayCount\":4").contains("\"streakDays\":9")
                .contains("\"monthRecordDays\":18");
        // 嵌套的日历/周视图等大对象不应出现在工具结果里
        assertThat(json).doesNotContain("calendar").doesNotContain("week");
    }

    @Test
    void 月度统计省略年月时应落到当前月而不是报错() {
        MonthlyStatisticsService service = mock(MonthlyStatisticsService.class);
        LocalDate today = LocalDate.now();
        StatisticsDtos.MonthStats stats = StatisticsDtos.MonthStats.builder().recordDays(18).build();
        when(service.monthStats(USER_ID, today.getYear(), today.getMonthValue())).thenReturn(stats);

        String json = new StatisticsTools(mock(DailyStatisticsService.class), service, json())
                .getMonthStats(null, null, CONTEXT);

        assertThat(json).contains("\"year\":" + today.getYear())
                .contains("\"month\":" + today.getMonthValue())
                .contains("\"recordDays\":18");
    }

    @Test
    void 月度统计的月份越界应给出可读错误() {
        String json = new StatisticsTools(mock(DailyStatisticsService.class),
                mock(MonthlyStatisticsService.class), json())
                .getMonthStats(2026, 13, CONTEXT);

        assertThat(json).contains("error").contains("1-12");
    }

    // ---------- query_meal_records ----------

    @Test
    void 饮食工具应裁掉图片与内部主键字段() {
        MealRecordService mealService = mock(MealRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);

        MealVos.MealDetail meal = MealVos.MealDetail.builder()
                .id("9001")
                .nodeCode("lunch")
                .mediaId("123")
                .mediaUrl("http://localhost:9000/glucopilot-dev/3/2026/10/7/x.jpg")
                .foodsText("米饭、青菜、鸡胸肉")
                .carbsG(new BigDecimal("68.0"))
                .predictNow(new BigDecimal("9.2"))
                .build();
        MealVos.DayMeals day = MealVos.DayMeals.builder()
                .recordDate(LocalDate.now())
                .meals(List.of(meal))
                .build();
        when(mealService.listDay(eq(USER_ID), any(LocalDate.class))).thenReturn(day);

        GlucoseNode node = new GlucoseNode();
        node.setNodeCode("lunch");
        node.setNodeName("中餐");
        when(periodService.nodeMap()).thenReturn(Map.of("lunch", node));

        String json = new MealRecordTools(mealService, periodService, json())
                .queryMealRecords(1, CONTEXT);

        assertThat(json).contains("米饭、青菜、鸡胸肉").contains("中餐").contains("carbsG");
        // 模型看不到图片，URL 与 mediaId 只会白花 token
        assertThat(json).doesNotContain("mediaUrl").doesNotContain("localhost:9000")
                .doesNotContain("mediaId");
        assertThat(json).doesNotContain("\"id\"");
    }

    @Test
    void 饮食工具超出天数上限应给出可读错误() {
        String json = new MealRecordTools(mock(MealRecordService.class),
                mock(GlucosePeriodService.class), json())
                .queryMealRecords(30, CONTEXT);

        assertThat(json).contains("error").contains("7");
    }

    // ---------- lookup_food_nutrition ----------

    @Test
    void 食物工具应裁掉主键与图片且给出总数() {
        FoodService foodService = mock(FoodService.class);
        FoodDtos.FoodItem food = FoodDtos.FoodItem.builder()
                .id("501")
                .foodName("米饭")
                .categoryName("谷薯类")
                .imageUrl("http://cdn/x.jpg")
                .carbsPer100g(new BigDecimal("25.9"))
                .caloriesPer100g(new BigDecimal("116"))
                .giValue(new BigDecimal("83.2"))
                .build();
        when(foodService.pageFoods(any(), any(), eq("米饭"), any()))
                .thenReturn(PageResult.of(1, 5, 12, List.of(food)));

        String json = new FoodNutritionTools(foodService, json()).lookupFoodNutrition("米饭");

        assertThat(json).contains("米饭").contains("25.9").contains("\"total\":12");
        assertThat(json).doesNotContain("imageUrl").doesNotContain("cdn/x.jpg");
        assertThat(json).doesNotContain("\"id\"");
    }

    @Test
    void 食物工具关键词为空应给出可读错误() {
        String json = new FoodNutritionTools(mock(FoodService.class), json()).lookupFoodNutrition("  ");
        assertThat(json).contains("error").contains("关键词");
    }

    // ---------- query_glucose_range：聚合摘要与易误解字段 ----------

    @Test
    void 血糖工具应带聚合摘要且剔除易被误解的pct() {
        GlucoseRecordService recordService = mock(GlucoseRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);
        GlucoseTrendService trendService = mock(GlucoseTrendService.class);

        GlucoseRecord row = new GlucoseRecord();
        row.setRecordDate(LocalDate.now());
        row.setNodeCode("before_breakfast");
        row.setGlucoseValue(new BigDecimal("6.8"));
        when(recordService.listRange(anyLong(), any(), any())).thenReturn(List.of(row));

        GlucoseNode node = new GlucoseNode();
        node.setNodeCode("before_breakfast");
        node.setNodeName("早餐前");
        node.setIsMeal(0);
        when(periodService.nodeMap()).thenReturn(Map.of("before_breakfast", node));
        when(periodService.slotStats(any())).thenReturn(List.of(
                GlucoseDtos.SlotStat.builder().nodeCode("breakfast").name("早餐段")
                        .count(3).avg(new BigDecimal("7.2")).pct(100).build()));
        when(trendService.dailySeries(any(), any(), any())).thenReturn(List.of(
                GlucoseDtos.TrendPoint.builder().date(LocalDate.now())
                        .avg(new BigDecimal("6.8")).count(1).build()));

        String json = new GlucoseQueryTools(recordService, periodService, trendService, json())
                .queryGlucoseRange(null, null, 7, CONTEXT);

        assertThat(json).contains("\"summary\"").contains("\"daily\"").contains("\"slots\"");
        assertThat(json).contains("早餐段").contains("6.8");
        // pct 是"端上进度条比例"，不是达标率；回给模型会被误读成达标率
        assertThat(json).doesNotContain("pct");
    }
}
