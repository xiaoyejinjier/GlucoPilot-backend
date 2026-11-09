package com.threadfit.glucopilot.community.report.controller;

import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.report.service.CommunityReportService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 社区举报（管理端）的运营接口。
 *
 * <h2>为什么这里不再要求 hasRole('admin')</h2>
 * 写操作原本写的是 @ss.hasRole('admin') and @ss.hasPermi('content:news:edit')，但 RuoYi 的
 * hasRole('admin') 只对 user_id=1（超管）成立——它把「谁能治理社区」这件事钉死在了一个账号上，
 * 运营一旦换人就得改代码。授权应当由菜单/权限点决定：现在只要求 content:news:edit，
 * 谁能拿到这个权限点，谁就能审核/隐藏/恢复，增减人只改 sys_role_menu。
 *
 * <h2>读接口仍是 content:news:list</h2>
 * 读与写分属两个权限点：只给「看」的角色（如只读运营）不该因为能看列表就获得审核权。
 */@RestController
@RequestMapping("/admin/v1/community")
@RequiredArgsConstructor
public class AdminCommunityReportController {
    private final CommunityReportService service;

    @GetMapping("/reports")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<Map<String, Object>>> reports(
            @RequestParam(required = false) String status, @Valid PageQuery q) {
        return ApiResponse.ok(service.reports(status, q));
    }

    @PostMapping("/reports/{id}/resolve")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> resolve(
            @PathVariable long id, @Valid @RequestBody Resolution request) {
        service.resolveReport(RequestContext.requireOpsId(), id, request);
        return ApiResponse.ok();
    }
}
