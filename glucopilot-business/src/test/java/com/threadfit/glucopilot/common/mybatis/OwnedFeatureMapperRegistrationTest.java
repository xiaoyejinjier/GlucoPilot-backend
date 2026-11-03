package com.threadfit.glucopilot.common.mybatis;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.GlucoPilotBackendApplication;
import com.threadfit.glucopilot.file.mapper.AvatarMediaMapper;
import com.threadfit.glucopilot.glucose.mapper.GlucoseAlertMapper;
import com.threadfit.glucopilot.glucose.mapper.GlucoseAlertQuery;
import com.threadfit.glucopilot.localization.mapper.LocalizationMapper;
import com.threadfit.glucopilot.localization.mapper.LocalizationSql;
import com.threadfit.glucopilot.meal.mapper.MealPhotoMapper;
import com.threadfit.glucopilot.notification.mapper.AdminWorkReminderMapper;
import com.threadfit.glucopilot.statistics.mapper.MealPredictionStatisticsMapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

class OwnedFeatureMapperRegistrationTest {
    @Test
    void productionScanIncludesEveryMigratedMapperAndExcludesQueryHelpers() throws Exception {
        MapperScan scan = GlucoPilotBackendApplication.class.getAnnotation(MapperScan.class);
        for (String root : List.of("statistics", "notification", "localization")) {
            assertTrue(
                    Arrays.asList(scan.value())
                            .contains("com.threadfit.glucopilot." + root + ".mapper"));
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:owned_mapper_registration;MODE=MySQL", "sa", ""));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = factory.getObject();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("sqlSessionFactory", SqlSessionFactory.class, () -> sessions);
            context.registerBean(
                    MapperScannerConfigurer.class,
                    () -> {
                        var scanner = new MapperScannerConfigurer();
                        scanner.setBasePackage(
                                String.join(
                                        ",",
                                        Arrays.stream(scan.value())
                                                .filter(root -> root.endsWith(".mapper"))
                                                .toList()));
                        scanner.setMarkerInterface(scan.markerInterface());
                        scanner.setAnnotationClass(scan.annotationClass());
                        scanner.setSqlSessionFactoryBeanName("sqlSessionFactory");
                        return scanner;
                    });
            context.refresh();
            for (Class<?> mapper :
                    List.of(
                            GlucoseAlertMapper.class,
                            MealPhotoMapper.class,
                            AvatarMediaMapper.class,
                            MealPredictionStatisticsMapper.class,
                            AdminWorkReminderMapper.class,
                            LocalizationMapper.class)) {
                assertNotNull(context.getBean(mapper));
            }
            assertTrue(context.getBeansOfType(GlucoseAlertQuery.class).isEmpty());
            assertTrue(context.getBeansOfType(LocalizationSql.class).isEmpty());
        }
    }

    @Test
    void ownedProductionFeaturesHaveNoDirectJdbcQueries() throws Exception {
        Path module =
                Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                        .getParent()
                        .getParent();
        Path source = module.resolve("src/main/java/com/threadfit/glucopilot");
        assertTrue(Files.isDirectory(source));
        var forbidden =
                Pattern.compile(
                        "org\\.springframework\\.jdbc\\.(core|object)"
                                + "|\\b(JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)\\b"
                                + "|java\\.sql\\.(Connection|PreparedStatement|Statement|ResultSet)"
                                + "|\\.prepareStatement\\s*\\(");
        var violations = new ArrayList<String>();
        for (String feature :
                List.of(
                        "community",
                        "food",
                        "glucose",
                        "statistics",
                        "meal",
                        "export",
                        "user",
                        "notification",
                        "localization")) {
            try (var files = Files.walk(source.resolve(feature))) {
                for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (forbidden.matcher(Files.readString(path)).find()) {
                        violations.add(source.relativize(path).toString());
                    }
                }
            }
        }
        Path avatars = source.resolve("file/service/AvatarMediaService.java");
        assertFalse(forbidden.matcher(Files.readString(avatars)).find());
        assertTrue(violations.isEmpty(), violations.toString());
    }
}
