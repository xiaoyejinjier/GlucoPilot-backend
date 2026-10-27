package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FoodRepeatedDeletionSqlTest {
    @Test
    void repeatedRecognitionAndDeletionKeepHistoryButStillRejectDuplicateLiveNames() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:food_repeated_delete_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        String schema = resource("V1__init_schema.sql");
        int start = schema.indexOf("CREATE TABLE food_nutrition (");
        int end = schema.indexOf(") ENGINE=", start);
        try (var connection = ds.getConnection(); var ddl = connection.createStatement()) {
            ddl.execute(schema.substring(start, end + 1).replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY"));
            ddl.execute("ALTER TABLE food_nutrition ADD nutrition_source VARCHAR(32) DEFAULT 'MANUAL' NOT NULL");
            ddl.execute("ALTER TABLE food_nutrition ADD source_model VARCHAR(128)");
            ddl.execute(resource("V30__food_recognition_source_photo.sql"));
            // H2 does not support MySQL's VIRTUAL and compound ALTER index syntax.
            String migration = resource("V42__food_active_name_uniqueness.sql");
            String expression = migration.substring(migration.indexOf("ADD COLUMN"), migration.indexOf(" VIRTUAL"));
            ddl.execute("ALTER TABLE food_nutrition " + expression);
            ddl.execute("ALTER TABLE food_nutrition DROP CONSTRAINT uk_cat_name");
            String index = migration.substring(migration.indexOf("ADD UNIQUE KEY") + "ADD UNIQUE KEY ".length()).trim();
            ddl.execute("CREATE UNIQUE INDEX " + index.replaceFirst(" \\(", " ON food_nutrition ("));

            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
            config.addMapper(FoodNutritionMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(FoodNutritionMapper.class);
                var categories = mock(com.threadfit.glucopilot.food.mapper.FoodCategoryMapper.class);
                var category = new com.threadfit.glucopilot.food.entity.FoodCategory(); category.setId(1L); category.setCategoryName("主食类");
                when(categories.selectById(1L)).thenReturn(category);
                when(categories.selectList(null)).thenReturn(java.util.List.of(category));
                var service = new AdminFoodService(categories, mapper);
                var clientFoods = new FoodService(categories, mapper);
                RequestContext.setOpsId(9L);
                for (int version = 0; version < 3; version++) {
                    var food = new FoodNutrition(); food.setFoodName("小笼包"); food.setCategoryCode(1);
                    food.setCategoryId(1L); food.setCarbsPer100g(new BigDecimal("20")); food.setCaloriesPer100g(new BigDecimal("120"));
                    food.setCreatedBy(0L); food.setUpdatedBy(0L); food.setSourceModel("test"); food.setSourceMediaId(88L + version);
                    mapper.insertRecognitionDraft(food);
                    Long id = jdbc.queryForObject("SELECT id FROM food_nutrition WHERE is_deleted=0", Long.class);
                    // Repeated recognition uses the same active row and retains its first photo.
                    food.setSourceMediaId(999L); mapper.insertRecognitionDraft(food);
                    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM food_nutrition WHERE is_deleted=0", Integer.class));
                    assertEquals(88L + version, mapper.selectById(id).getSourceMediaId());
                    // A saved administrator upload remains canonical after re-reading SQL and enabling.
                    String uploaded = "/api/v1/foods/images/" + (72 + version);
                    var change = new com.threadfit.glucopilot.food.dto.AdminFoodDtos.FoodSaveRequest();
                    change.setId(id); change.setName("小笼包"); change.setCategoryId(1L);
                    change.setImage(uploaded); change.setEnabled(true);
                    assertEquals(uploaded, service.saveFood(change).getImage());
                    assertEquals(uploaded, jdbc.queryForObject("SELECT image_url FROM food_nutrition WHERE id=?",String.class,id));
                    assertEquals(uploaded, clientFoods.getFoodDetail(id).getImageUrl());
                    assertEquals(88L + version, mapper.selectById(id).getSourceMediaId());
                    change.setImage(null); change.setEnabled(false); service.saveFood(change);
                    change.setEnabled(true); service.saveFood(change);
                    assertEquals(uploaded, clientFoods.getFoodDetail(id).getImageUrl());
                    service.removeFood(id);
                    assertNull(mapper.selectById(id));
                    assertEquals(88L + version, jdbc.queryForObject("SELECT source_media_id FROM food_nutrition WHERE id=?", Long.class, id));
                    assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM food_nutrition WHERE id=?", java.sql.Timestamp.class, id));
                    assertEquals(9L, jdbc.queryForObject("SELECT deleted_by FROM food_nutrition WHERE id=?", Long.class, id));
                }
                assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM food_nutrition WHERE is_deleted=1 AND food_name='小笼包'", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM food_nutrition WHERE active_name_marker IS NOT NULL", Integer.class));
            } finally { RequestContext.clear(); jdbc.execute("DROP ALL OBJECTS"); }
        }
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
