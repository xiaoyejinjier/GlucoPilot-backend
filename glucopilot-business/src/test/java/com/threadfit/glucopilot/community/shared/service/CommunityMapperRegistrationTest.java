package com.threadfit.glucopilot.community.shared.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.GlucoPilotBackendApplication;
import com.threadfit.glucopilot.community.access.mapper.ArticleAccessSql;
import com.threadfit.glucopilot.community.article.mapper.CommunityArticleQuery;
import com.threadfit.glucopilot.community.media.mapper.CommunityArticleImageMapper;
import com.threadfit.glucopilot.community.message.mapper.AdminMessageMapper;
import com.threadfit.glucopilot.community.recommendation.mapper.AdminRecommendationMapper;
import com.threadfit.glucopilot.community.recommendation.mapper.CommunityRecommendationMapper;
import com.threadfit.glucopilot.community.shared.mapper.CommunityAccountCleanupMapper;
import com.threadfit.glucopilot.export.mapper.DataExportMapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;

class CommunityMapperRegistrationTest {
    @Test
    void productionScanRegistersPlusAndCompositeKeyReadMappers() throws Exception {
        MapperScan scan = GlucoPilotBackendApplication.class.getAnnotation(MapperScan.class);
        assertTrue(Arrays.asList(scan.value()).contains("com.threadfit.glucopilot.community"));
        assertTrue(Arrays.asList(scan.value()).contains("com.threadfit.glucopilot.export.mapper"));
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:community_mapper_registration;MODE=MySQL", "sa", "");
        var factory = new MybatisSqlSessionFactoryBean();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = factory.getObject();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("sqlSessionFactory", SqlSessionFactory.class, () -> sessions);
            context.registerBean(
                    MapperScannerConfigurer.class,
                    () -> {
                        var scanner = new MapperScannerConfigurer();
                        scanner.setBasePackage(
                                "com.threadfit.glucopilot.community,com.threadfit.glucopilot.export.mapper");
                        scanner.setMarkerInterface(scan.markerInterface());
                        scanner.setAnnotationClass(scan.annotationClass());
                        scanner.setSqlSessionFactoryBeanName("sqlSessionFactory");
                        return scanner;
                    });
            context.refresh();
            assertNotNull(
                    context.getBean(
                            com.threadfit.glucopilot.community.comment.mapper.CommunityCommentMapper
                                    .class));
            assertNotNull(context.getBean(CommunityArticleImageMapper.class));
            assertNotNull(context.getBean(AdminMessageMapper.class));
            assertNotNull(context.getBean(CommunityRecommendationMapper.class));
            assertNotNull(context.getBean(AdminRecommendationMapper.class));
            assertNotNull(context.getBean(DataExportMapper.class));
            assertNotNull(context.getBean(CommunityAccountCleanupMapper.class));
            assertEquals(10, context.getBeansOfType(BaseMapper.class).size());
            assertTrue(context.getBeansOfType(CommunityArticleQuery.class).isEmpty());
            assertTrue(context.getBeansOfType(ArticleAccessSql.class).isEmpty());
        }
    }
}
