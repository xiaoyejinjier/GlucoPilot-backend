package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.user.settings.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** C 端公开配置（匿名可访问），供免责声明文案、血糖阈值与展示默认值使用。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AppConfigController {

    private final AppConfigService appConfigService;

    @GetMapping("/app-config")
    public ApiResponse<Map<String, Map<String, Object>>> appConfig(
            @RequestParam(required = false) String groups) {
        return ApiResponse.ok(appConfigService.publicConfigs(parseGroups(groups)));
    }

    private static List<String> parseGroups(String groups) {
        if (!StringUtils.hasText(groups)) {
            return List.of();
        }
        return Arrays.stream(groups.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
