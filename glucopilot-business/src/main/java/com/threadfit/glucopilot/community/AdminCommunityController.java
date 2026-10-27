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
     * 评论审核列表（含被 AI 隐藏的评论）。
     * <p>
     * 与恢复接口配套：这张列表回答问题"今天被藏了哪些、为什么"，恢复接口负责"藏错了要放开"。
     * 只有列表没有恢复，误判无法纠正；只有恢复没有列表，运营根本找不出误判。
     */
    @GetMapping("/comments")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<AdminComment>> comments(@RequestParam(required=false) String status,
            @RequestParam(required=false) String verdict,@RequestParam(required=false) Integer hidden,
            @RequestParam(required=false) String keyword,@Valid PageQuery q) {
        return ApiResponse.ok(service.adminComments(status,verdict,hidden,keyword,q));
    }

    /**
     * 主动设置一条评论的可见性：隐藏 / 恢复显示。
     * <p>
     * 为什么需要"主动隐藏"：AI 只能判它见过的内容，而运营会遇到机器放过、实际该压下去的东西
     * （广告变体、擦边、踩线话题）。只有"恢复"没有"隐藏"，运营手里就只有一半的权力。
     * 与 AI 判违规共用同一列 {@code is_hidden}，但不动 {@code ai_verdict}：两种来源的事实都保留。
     * <p>
     * 幂等：已经处于目标状态时返回成功（重复点击不报错）；评论不存在或已删除返回 404。
     */
    @PutMapping("/comments/{id}/visibility")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> setCommentVisibility(@PathVariable long id,
            @Valid @RequestBody CommentVisibility request) {
        queue.setCommentHidden(RequestContext.requireOpsId(), id, request.hidden());
        return ApiResponse.ok();
    }

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
