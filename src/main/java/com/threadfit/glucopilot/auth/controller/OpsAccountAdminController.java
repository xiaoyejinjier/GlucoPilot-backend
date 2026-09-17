package com.threadfit.glucopilot.auth.controller;

import com.threadfit.glucopilot.auth.dto.OpsAccountAdminDtos;
import com.threadfit.glucopilot.auth.service.OpsAccountAdminService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/v1/ops-accounts")
@RequiredArgsConstructor
public class OpsAccountAdminController {

    private final OpsAccountAdminService opsAccountAdminService;

    @GetMapping
    public ApiResponse<List<OpsAccountAdminDtos.AccountItem>> list() {
        return ApiResponse.ok(opsAccountAdminService.list());
    }

    @PostMapping
    public ApiResponse<OpsAccountAdminDtos.AccountItem> create(
            @Valid @RequestBody OpsAccountAdminDtos.CreateRequest request) {
        return ApiResponse.ok(opsAccountAdminService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<OpsAccountAdminDtos.AccountItem> update(
            @PathVariable Long id, @Valid @RequestBody OpsAccountAdminDtos.UpdateRequest request) {
        return ApiResponse.ok(opsAccountAdminService.update(id, request));
    }
}
