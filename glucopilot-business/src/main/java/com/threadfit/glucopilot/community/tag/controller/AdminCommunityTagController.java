package com.threadfit.glucopilot.community.tag.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.tag.dto.CommunityTagDtos;
import com.threadfit.glucopilot.community.tag.service.CommunityTagService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 社区标签（管理端）的运营接口。
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
@RequestMapping("/admin/v1/community/tags")
public class AdminCommunityTagController {
    private final CommunityTagService service;

    public AdminCommunityTagController(CommunityTagService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<CommunityTagDtos.Row>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer enabled,
            @Valid PageQuery page) {
        RequestContext.requireOpsId();
        return ApiResponse.ok(service.list(keyword, type, enabled, page));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<CommunityTagDtos.Row> create(
            @Valid @RequestBody CommunityTagDtos.Change request) {
        RequestContext.requireOpsId();
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{code}")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<CommunityTagDtos.Row> update(
            @PathVariable String code, @Valid @RequestBody CommunityTagDtos.Change request) {
        RequestContext.requireOpsId();
        return ApiResponse.ok(service.update(code, request));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> remove(@PathVariable String code) {
        RequestContext.requireOpsId();
        service.remove(code);
        return ApiResponse.ok();
    }
}
