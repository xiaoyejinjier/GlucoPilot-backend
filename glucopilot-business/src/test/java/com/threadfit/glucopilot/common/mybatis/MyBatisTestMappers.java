package com.threadfit.glucopilot.common.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** JDBC仅准备测试数据；被测业务使用真实MyBatis并共享同一Spring事务数据源。 */
public final class MyBatisTestMappers {
    private MyBatisTestMappers() {}

    public static <T> T mapper(JdbcTemplate fixture, Class<T> type) {
        try {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            configuration.addMapper(type);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(Objects.requireNonNull(fixture.getDataSource()));
            factory.setConfiguration(configuration);
            var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
            return session.getMapper(type);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot create typed MyBatis test mapper", error);
        }
    }
}
