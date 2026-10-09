package com.threadfit.glucopilot.user.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.user.account.AdminUserService;
import com.threadfit.glucopilot.user.dto.AdminUserDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端 C 端用户查询与账号状态管理。 */
@RestController
@RequestMapping("/admin/v1/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.USER_MANAGE + "')")
    public ApiResponse<PageResult<AdminUserDtos.UserItem>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer accountStatus,
            @Valid PageQuery query) {
        return ApiResponse.ok(adminUserService.page(keyword, accountStatus, query));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.USER_MANAGE + "')")
    public ApiResponse<AdminUserDtos.UpdatedResult> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserDtos.StatusRequest request) {
        return ApiResponse.ok(adminUserService.updateStatus(id, request.getAccountStatus()));
    }
}
