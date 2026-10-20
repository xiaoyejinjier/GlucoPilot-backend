package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
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

class FoodRecognitionPhotoSqlTest {
    @Test void actualMigrationAndMapperKeepFirstPhotoAndDoNotChangeReviewedFood() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:food_photo_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        String schema = resource("V1__init_schema.sql");
        int start = schema.indexOf("CREATE TABLE food_nutrition (");
        int end = schema.indexOf(") ENGINE=", start);
        // H2的CHECK表达式保留DDL会话，整个验收期间保持该连接有效。
        try (var schemaConnection = ds.getConnection(); var ddl = schemaConnection.createStatement()) {
        ddl.execute(schema.substring(start, end + 1).replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY"));
        ddl.execute("ALTER TABLE food_nutrition ADD nutrition_source VARCHAR(32) DEFAULT 'MANUAL' NOT NULL");
        ddl.execute("ALTER TABLE food_nutrition ADD source_model VARCHAR(128)");
        ddl.execute(resource("V23__food_recognition_source_photo.sql"));
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(FoodNutritionMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(FoodNutritionMapper.class);
            FoodNutrition draft = new FoodNutrition(); draft.setFoodName("实拍新食品"); draft.setCategoryCode(1);
            draft.setCarbsPer100g(new BigDecimal("20")); draft.setCaloriesPer100g(new BigDecimal("120"));
            draft.setCreatedBy(0L); draft.setUpdatedBy(0L); draft.setSourceModel("test"); draft.setSourceMediaId(88L);
            mapper.insertRecognitionDraft(draft);
            Long id = jdbc.queryForObject("SELECT id FROM food_nutrition", Long.class);
            assertEquals(88L, mapper.selectById(id).getSourceMediaId());
            draft.setSourceMediaId(99L); mapper.insertRecognitionDraft(draft);
            assertEquals(88L, mapper.selectById(id).getSourceMediaId());
            jdbc.update("UPDATE food_nutrition SET source_media_id=NULL WHERE id=?", id);
            assertEquals(1, mapper.attachRecognitionPhoto(id, 99L));
            assertEquals(0, mapper.attachRecognitionPhoto(id, 88L));
            jdbc.update("UPDATE food_nutrition SET source_media_id=NULL,nutrition_source='AI_REVIEWED' WHERE id=?", id);
            assertEquals(0, mapper.attachRecognitionPhoto(id, 88L));
            assertNull(mapper.selectById(id).getSourceMediaId());
        } finally { jdbc.execute("DROP ALL OBJECTS"); }
        }
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
