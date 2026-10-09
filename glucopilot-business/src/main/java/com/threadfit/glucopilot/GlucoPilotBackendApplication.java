package com.threadfit.glucopilot;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mapper 扫描约定：
 * <ul>
 *   <li>只扫各模块 {@code *.mapper} 包，禁止扫 {@code com.threadfit.glucopilot} 全包</li>
 *   <li>仅注册继承 {@link BaseMapper} 的接口</li>
 *   <li>否则 {@code port}/{@code client}/{@code CodeEnum} 会被当成 Mapper，
 *       运行时报 Invalid bound statement（曾踩：把 port/client 扫成 Mapper）</li>
 * </ul>
 */
@org.springframework.context.annotation.Profile("upstream-standalone")
@SpringBootApplication
@MapperScan(
        value = {
                "com.threadfit.glucopilot.auth.mapper",
                "com.threadfit.glucopilot.ruoyi.mapper",
                "com.threadfit.glucopilot.content.mapper",
//                "com.threadfit.glucopilot.device.mapper",
                "com.threadfit.glucopilot.ai.business.chat.mapper",
                "com.threadfit.glucopilot.food.mapper",
                "com.threadfit.glucopilot.glucose.mapper",
                "com.threadfit.glucopilot.user.mapper",
                "com.threadfit.glucopilot.profile.health.mapper",
                "com.threadfit.glucopilot.file.mapper",
                "com.threadfit.glucopilot.meal.mapper"
        },
        markerInterface = BaseMapper.class
)
public class GlucoPilotBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlucoPilotBackendApplication.class, args);
    }

}
