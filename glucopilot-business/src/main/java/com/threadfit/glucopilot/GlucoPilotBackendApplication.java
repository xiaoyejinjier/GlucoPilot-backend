package com.threadfit.glucopilot;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mapper 扫描约定：
 *
 * <ul>
 *   <li>扫描各模块Mapper包；社区按功能分包后递归扫描community，禁止扫描整个业务根包
 *   <li>仅注册继承 {@link BaseMapper} 或显式标注 MyBatis {@code @Mapper} 的接口
 *   <li>否则 {@code port}/{@code client}/{@code CodeEnum} 会被当成 Mapper， 运行时报 Invalid bound
 *       statement（曾踩：把 port/client 扫成 Mapper）
 * </ul>
 */
@org.springframework.context.annotation.Profile("upstream-standalone")
@SpringBootApplication
@MapperScan(
        value = {
            "com.threadfit.glucopilot.auth.mapper",
            "com.threadfit.glucopilot.ruoyi.mapper",
            "com.threadfit.glucopilot.content.mapper",
            "com.threadfit.glucopilot.export.mapper",
            "com.threadfit.glucopilot.community",
            //                "com.threadfit.glucopilot.device.mapper",
            "com.threadfit.glucopilot.ai.business.chat.mapper",
            "com.threadfit.glucopilot.food.mapper",
            "com.threadfit.glucopilot.glucose.mapper",
            "com.threadfit.glucopilot.statistics.mapper",
            "com.threadfit.glucopilot.notification.mapper",
            "com.threadfit.glucopilot.localization.mapper",
            "com.threadfit.glucopilot.user.mapper",
            "com.threadfit.glucopilot.profile.health.mapper",
            "com.threadfit.glucopilot.file.mapper",
            "com.threadfit.glucopilot.meal.mapper"
        },
        markerInterface = BaseMapper.class,
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class GlucoPilotBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlucoPilotBackendApplication.class, args);
    }
}
