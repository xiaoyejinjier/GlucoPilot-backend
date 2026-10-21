package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.threadfit.glucopilot.community.PublicationDtos.*;

@RestController
@RequestMapping("/admin/v1/community")
public class AdminCommunityController {
    private final CommunityPublicationService service;
    private final CommunityModerationQueue queue;
    public AdminCommunityController(CommunityPublicationService service, CommunityModerationQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @GetMapping("/articles")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<Article>> articles(@RequestParam(required=false) String status,
            @RequestParam(required=false) String source,@RequestParam(required=false) String keyword,@Valid PageQuery q) {
        return ApiResponse.ok(service.adminArticles(status,source,keyword,q));
    }
    @GetMapping("/articles/{id}")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<Article> article(@PathVariable long id) { return ApiResponse.ok(service.get(null,id,true)); }

    @PostMapping("/articles/{id}/review")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Article> review(@PathVariable long id,@Valid @RequestBody Review request) {
        return ApiResponse.ok(service.review(RequestContext.requireOpsId(),id,request));
    }
    @PostMapping("/articles/{id}/retry-ai")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> retry(@PathVariable long id) { service.retry(RequestContext.requireOpsId(),id,true);return ApiResponse.ok(); }

    /**
     * 恢复被 AI 判违规并隐藏的评论（误杀兜底）。
     * <p>
     * 为什么必须有人工恢复入口：评论是"先发后审"，机器判 REJECT 会立即隐藏；
     * 没有恢复入口意味着一次误判只能改库才能纠正，而误判一定会有。
     * 幂等：评论不存在或本来就没有被隐藏时返回成功（运营重复点击不该报错）。
     */
    @PostMapping("/comments/{id}/restore")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> restoreComment(@PathVariable long id) {
        queue.restoreComment(RequestContext.requireOpsId(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/reports")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<Map<String,Object>>> reports(@RequestParam(required=false) String status,@Valid PageQuery q) {
        return ApiResponse.ok(service.reports(status,q));
    }
    @PostMapping("/reports/{id}/resolve")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> resolve(@PathVariable long id,@Valid @RequestBody Resolution request) {
        service.resolveReport(RequestContext.requireOpsId(),id,request);return ApiResponse.ok();
    }
}
