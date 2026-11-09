package com.threadfit.glucopilot.community.article.controller;

import static com.threadfit.glucopilot.community.article.dto.PublicationDtos.*;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.article.service.CommunityPublicationService;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 社区文章（管理端）的运营接口。
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
public class AdminCommunityController {
    private final CommunityPublicationService service;
    private final CommunityModerationQueue queue;

    public AdminCommunityController(
            CommunityPublicationService service, CommunityModerationQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @GetMapping("/articles")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<Article>> articles(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery q) {
        return ApiResponse.ok(service.adminArticles(status, source, keyword, q));
    }

    @GetMapping("/articles/{id}")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<Article> article(@PathVariable long id) {
        return ApiResponse.ok(service.get(null, id, true));
    }

    @PostMapping("/articles/{id}/review")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<Article> review(@PathVariable long id, @Valid @RequestBody Review request) {
        return ApiResponse.ok(service.review(RequestContext.requireOpsId(), id, request));
    }

    @PostMapping("/articles/{id}/retry-ai")
    @PreAuthorize("@ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> retry(@PathVariable long id) {
        service.retry(RequestContext.requireOpsId(), id, true);
        return ApiResponse.ok();
    }
}
