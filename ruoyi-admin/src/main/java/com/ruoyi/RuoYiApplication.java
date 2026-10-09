package com.ruoyi;

import com.threadfit.glucopilot.adapter.GlucoPilotConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Official RuoYi entry point with GlucoPilot business and compatibility APIs. */
@SpringBootApplication(
    excludeName = "com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure")
@Import(GlucoPilotConfiguration.class)
public class RuoYiApplication {
  public static void main(String[] args) {
    SpringApplication.run(RuoYiApplication.class, args);
  }
}
