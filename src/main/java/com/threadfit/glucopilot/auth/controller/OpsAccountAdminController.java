package com.threadfit.glucopilot.auth.controller;

import com.threadfit.glucopilot.auth.dto.OpsAccountAdminDtos;
import com.threadfit.glucopilot.auth.service.OpsAccountAdminService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/admin/v1/ops-accounts")
@RequiredArgsConstructor
public class OpsAccountAdminController {

    private final OpsAccountAdminService opsAccountAdminService;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.USER_MANAGE + "')")
    public ApiResponse<List<OpsAccountAdminDtos.AccountItem>> list() {
        return ApiResponse.ok(opsAccountAdminService.list());
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.USER_MANAGE + "')")
    public ApiResponse<OpsAccountAdminDtos.AccountItem> create(
            @Valid @RequestBody OpsAccountAdminDtos.CreateRequest request) {
        return ApiResponse.ok(opsAccountAdminService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.USER_MANAGE + "')")
    public ApiResponse<OpsAccountAdminDtos.AccountItem> update(
            @PathVariable Long id, @Valid @RequestBody OpsAccountAdminDtos.UpdateRequest request) {
        return ApiResponse.ok(opsAccountAdminService.update(id, request));
    }
}
