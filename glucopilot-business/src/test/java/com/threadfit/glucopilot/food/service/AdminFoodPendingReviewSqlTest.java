package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import com.threadfit.glucopilot.notification.AdminWorkReminderService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminFoodPendingReviewSqlTest {
    @Test void recognitionPaginationApprovalAndDeletionAgreeWithRealReminderSql() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:food_pending_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        String schema = resource("V1__init_schema.sql");
        try (var connection = ds.getConnection(); var ddl = connection.createStatement()) {
            for (String table : new String[]{"food_category", "food_nutrition"}) {
                int start = schema.indexOf("CREATE TABLE " + table + " (");
                ddl.execute(schema.substring(start, schema.indexOf(") ENGINE=", start) + 1)
                        .replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY"));
            }
            ddl.execute(resource("V14__add_food_category_image.sql"));
            // H2 requires separate ADD statements; production uses the unchanged MySQL migration.
            ddl.execute("ALTER TABLE food_nutrition ADD nutrition_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL'");
            ddl.execute("ALTER TABLE food_nutrition ADD source_model VARCHAR(128)");
            ddl.execute(resource("V30__food_recognition_source_photo.sql"));
            jdbc.update("INSERT INTO food_category(id,category_name) VALUES(1,'主食类'),(2,'水果类')");
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
            var pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            config.addInterceptor(pagination);
            config.addMapper(FoodNutritionMapper.class); config.addMapper(FoodCategoryMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(FoodNutritionMapper.class);
                var service = new AdminFoodService(session.getMapper(FoodCategoryMapper.class), mapper);
                var reminders = new AdminWorkReminderService(jdbc);
                var grants = Set.of("food:library:manage");
                for (int i = 1; i <= 6; i++) {
                    var food = new FoodNutrition(); food.setFoodName("食品" + i);
                    food.setCategoryId(i == 2 ? 2L : 1L); food.setCategoryCode(i == 2 ? 4 : 1);
                    food.setCarbsPer100g(new BigDecimal("20")); food.setCaloriesPer100g(new BigDecimal("120"));
                    food.setCreatedBy(0L); food.setUpdatedBy(0L); mapper.insertRecognitionDraft(food);
                }
                jdbc.update("UPDATE food_nutrition SET nutrition_source='AI_REVIEWED' WHERE food_name='食品3'");
                jdbc.update("UPDATE food_nutrition SET nutrition_source='MANUAL' WHERE food_name='食品4'");
                jdbc.update("UPDATE food_nutrition SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP WHERE food_name='食品5'");
                jdbc.update("UPDATE food_nutrition SET shelf_status=1 WHERE food_name='食品6'");
                var query = new PageQuery(); query.setPageSize(1);
                var first = service.pageFoods(null, null, null, true, query);
                assertEquals(2, first.getTotal()); assertEquals(2, first.getPages()); assertEquals(1, first.getRecords().size());
                assertEquals(first.getTotal(), reminders.summary(grants).total());
                var response = org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(new com.threadfit.glucopilot.food.controller.AdminFoodController(service)).build()
                        .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/admin/v1/foods?pendingReview=true&pageSize=1")).andReturn().getResponse();
                assertEquals(200, response.getStatus());
                var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsByteArray()).path("data");
                assertEquals(2, data.path("total").asLong()); assertEquals(1, data.path("records").size());
                query.setPageNum(2);
                var second = service.pageFoods(null, null, null, true, query);
                assertEquals(2, second.getTotal()); assertNotEquals(first.getRecords().getFirst().getId(), second.getRecords().getFirst().getId());
                query.setPageNum(1);
                assertEquals(1, service.pageFoods(2L, "食品2", null, true, query).getTotal());
                assertEquals(0, service.pageFoods(null, null, true, true, query).getTotal());
                assertEquals(5, service.pageFoods(null, null, null, false, query).getTotal());
                var item = first.getRecords().getFirst();
                var request = new AdminFoodDtos.FoodSaveRequest(); request.setId(Long.valueOf(item.getId()));
                request.setName(item.getName()); request.setCategoryId(Long.valueOf(item.getCategoryId()));
                request.setEnabled(true);
                RequestContext.setOpsId(9L);
                assertEquals("AI_REVIEWED", service.saveFood(request).getNutritionSource());
                assertEquals(1, reminders.summary(grants).total());
                var remaining = service.pageFoods(null, null, null, true, query);
                assertEquals(1, remaining.getTotal());
                service.removeFood(Long.valueOf(remaining.getRecords().getFirst().getId()));
                assertEquals(0, reminders.summary(grants).total());
                assertEquals(0, service.pageFoods(null, null, null, true, query).getTotal());
            } finally { RequestContext.clear(); jdbc.execute("DROP ALL OBJECTS"); }
        }
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
