package com.threadfit.glucopilot.auth.controller;

import com.threadfit.glucopilot.auth.dto.RuoYiSystemDtos;
import com.threadfit.glucopilot.auth.service.RuoYiAuthorizationService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** RuoYi 风格的登录用户信息与动态菜单接口。 */
@RestController
@RequestMapping("/admin/v1/system")
@RequiredArgsConstructor
public class RuoYiSystemController {

    private final RuoYiAuthorizationService authorizationService;

    @GetMapping("/user-info")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RuoYiSystemDtos.UserInfo> userInfo() {
        return ApiResponse.ok(authorizationService.userInfoOf(RequestContext.requireOpsId()));
    }

    @GetMapping("/menus")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<RuoYiSystemDtos.MenuItem>> menus() {
        return ApiResponse.ok(authorizationService.menusOf(RequestContext.requireOpsId()));
    }
}
