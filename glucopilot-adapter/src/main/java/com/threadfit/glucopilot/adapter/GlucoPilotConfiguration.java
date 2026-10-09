package com.threadfit.glucopilot.adapter;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/** Keep business components distinct from upstream components with the same simple name. */
@Configuration
@org.springframework.context.annotation.EnableAspectJAutoProxy(exposeProxy = true)
@ComponentScan(
    basePackages = "com.threadfit.glucopilot",
    nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class GlucoPilotConfiguration {}
