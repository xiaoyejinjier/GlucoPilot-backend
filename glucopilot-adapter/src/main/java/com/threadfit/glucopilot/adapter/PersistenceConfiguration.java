package com.threadfit.glucopilot.adapter;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.github.pagehelper.PageInterceptor;
import com.ruoyi.framework.config.MyBatisConfig;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Separate plugin pipelines sharing the same datasource and transaction manager. */
@Configuration
public class PersistenceConfiguration {
  @Configuration
  @MapperScan(
      basePackages = {
        "com.ruoyi.system.mapper",
        "com.ruoyi.quartz.mapper",
        "com.ruoyi.generator.mapper"
      },
      sqlSessionFactoryRef = "ruoyiSqlSessionFactory")
  static class RuoYiMappers {}

  @Configuration
  @MapperScan(
      basePackages = "com.threadfit.glucopilot",
      markerInterface = com.baomidou.mybatisplus.core.mapper.BaseMapper.class,
      sqlSessionFactoryRef = "glucoSqlSessionFactory",
      nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
  static class BusinessMappers {}

  @Bean
  public SqlSessionFactory ruoyiSqlSessionFactory(DataSource dataSource) throws Exception {
    SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
    factory.setVfs(org.mybatis.spring.boot.autoconfigure.SpringBootVFS.class);
    factory.setDataSource(dataSource);
    factory.setTypeAliasesPackage(MyBatisConfig.setTypeAliasesPackage("com.ruoyi.**.domain"));
    factory.setMapperLocations(
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*Mapper.xml"));
    org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
    config.setMapUnderscoreToCamelCase(true);
    factory.setConfiguration(config);
    PageInterceptor page = new PageInterceptor();
    Properties props = new Properties();
    props.setProperty("helperDialect", "mysql");
    page.setProperties(props);
    factory.setPlugins(page);
    return factory.getObject();
  }

  @Bean
  @Primary
  public SqlSessionFactory glucoSqlSessionFactory(
      DataSource dataSource,
      MybatisPlusInterceptor interceptor,
      ObjectProvider<MetaObjectHandler> handlers)
      throws Exception {
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setVfs(org.mybatis.spring.boot.autoconfigure.SpringBootVFS.class);
    factory.setDataSource(dataSource);
    MybatisConfiguration config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    factory.setConfiguration(config);
    GlobalConfig global = new GlobalConfig();
    GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
    db.setIdType(IdType.AUTO);
    db.setLogicDeleteField("isDeleted");
    db.setLogicDeleteValue("1");
    db.setLogicNotDeleteValue("0");
    global.setDbConfig(db);
    global.setMetaObjectHandler(handlers.getIfAvailable());
    factory.setGlobalConfig(global);
    factory.setPlugins(interceptor);
    return factory.getObject();
  }
}
