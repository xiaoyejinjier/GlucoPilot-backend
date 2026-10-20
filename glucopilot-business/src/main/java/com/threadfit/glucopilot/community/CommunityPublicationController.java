package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static com.threadfit.glucopilot.community.PublicationDtos.*;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityPublicationController {
    private final CommunityPublicationService service;
    public CommunityPublicationController(CommunityPublicationService service) { this.service=service; }
    @GetMapping("/tags")
    public ApiResponse<List<Tag>> tags() { RequestContext.requireUserId();return ApiResponse.ok(service.tags()); }
    @GetMapping("/tags/mine")
    public ApiResponse<List<Tag>> myTags() {return ApiResponse.ok(service.myTags(RequestContext.requireUserId()));}
    @GetMapping("/profiles/{key}/articles")
    public ApiResponse<PageResult<Article>> authorArticles(@PathVariable String key,@Valid PageQuery q) {return ApiResponse.ok(service.byAuthor(RequestContext.requireUserId(),key,q));}
    @GetMapping("/articles")
    public ApiResponse<PageResult<Article>> articles(@RequestParam(required=false) String tag,@RequestParam(defaultValue="false") boolean following,@RequestParam(required=false) String keyword,@Valid PageQuery q) {
        return ApiResponse.ok(service.feed(RequestContext.requireUserId(),tag,following,keyword,q));
    }
    @GetMapping("/articles/mine")
    public ApiResponse<PageResult<Article>> mine(@Valid PageQuery q) { return ApiResponse.ok(service.mine(RequestContext.requireUserId(),q)); }
    @GetMapping("/articles/{id}")
    public ApiResponse<Article> article(@PathVariable long id) { return ApiResponse.ok(service.get(RequestContext.requireUserId(),id,false)); }
    @PostMapping("/articles")
    public ApiResponse<Article> submit(@Valid @RequestBody Submit request) { return ApiResponse.ok(service.submit(RequestContext.requireUserId(),request)); }
    @PutMapping("/articles/{id}")
    public ApiResponse<Article> edit(@PathVariable long id,@Valid @RequestBody Submit request) { return ApiResponse.ok(service.edit(RequestContext.requireUserId(),id,request)); }
    @PostMapping("/articles/{id}/retry-ai")
    public ApiResponse<Void> retry(@PathVariable long id) { service.retry(RequestContext.requireUserId(),id,false);return ApiResponse.ok(); }
    @PostMapping("/reports")
    public ApiResponse<Map<String,Object>> report(@Valid @RequestBody ReportRequest request) { return ApiResponse.ok(service.report(RequestContext.requireUserId(),request)); }
}
