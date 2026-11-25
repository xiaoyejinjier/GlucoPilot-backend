package com.threadfit.glucopilot.food.gi;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FoodGiMapperSqlTest {
    @Test void actualXmlClaimsOncePreservesManualGiAndRequeuesRenamedFood() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:gi_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE food_nutrition(id BIGINT PRIMARY KEY,food_name VARCHAR(128),category_code INT,"
                + "gi_value DECIMAL(5,1),is_deleted INT DEFAULT 0)");
        String script;
        try (var input = getClass().getResourceAsStream("/db/migration/R__food_gi_reference_catalog.sql")) {
            script = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        String ddl = script.substring(0, script.indexOf("START TRANSACTION;"))
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : ddl.split(";")) if (statement.contains("CREATE TABLE")) jdbc.execute(statement);
        jdbc.update("INSERT INTO food_nutrition(id,food_name,category_code) VALUES(1,'新食品',1)");
        jdbc.update("INSERT INTO food_nutrition(id,food_name,category_code,gi_value) VALUES(2,'人工维护',1,70)");
        var config = new Configuration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(FoodGiMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(FoodGiMapper.class);
            assertEquals(1, mapper.enqueueMissing());
            var item = mapper.pending().getFirst();
            assertEquals(1L, item.getFoodId());
            assertEquals(1, mapper.claim(item, "first"));
            assertEquals(0, mapper.claim(item, "second"));
            assertEquals(1L, mapper.lockClaim(1L, "first"));
            assertNull(mapper.lockClaim(1L, "second"));
            jdbc.update("UPDATE food_nutrition SET gi_value=65 WHERE id=1");
            assertEquals(0, mapper.fillGi(item, new BigDecimal("55")));
            assertEquals(new BigDecimal("65.0"), jdbc.queryForObject("SELECT gi_value FROM food_nutrition WHERE id=1", BigDecimal.class));
            mapper.complete(item, "first", FoodGiResult.unknown("No reliable match"));
            jdbc.update("UPDATE food_nutrition SET food_name='改名食品',gi_value=NULL WHERE id=1");
            mapper.enqueueMissing();
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM food_gi_lookup WHERE food_id=1", String.class));
            assertEquals(0, mapper.fillGi(item, new BigDecimal("55")));
            var renamed = mapper.pending().getFirst();
            assertEquals("改名食品", renamed.getFoodName());
            assertEquals(1, mapper.claim(renamed, "new"));
            assertEquals(1, mapper.fillGi(renamed, new BigDecimal("55")));
            assertEquals(1, mapper.complete(renamed,"new",new FoodGiResult("REFERENCE",new BigDecimal("55"),
                    "Trial food","https://example.test/study","Reference only")));
            assertEquals("https://example.test/study", jdbc.queryForObject("SELECT source_url FROM food_gi_lookup WHERE food_id=1", String.class));
            assertEquals(0, mapper.complete(renamed,"first",FoodGiResult.unknown("Late result")));
        } finally { jdbc.execute("DROP ALL OBJECTS"); }
    }
}
