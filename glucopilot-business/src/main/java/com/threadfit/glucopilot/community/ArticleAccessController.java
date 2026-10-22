package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/community")
public class ArticleAccessController {
    private final ArticleAccessService access;
    public ArticleAccessController(ArticleAccessService access){this.access=access;}
    @GetMapping("/friends") public ApiResponse<PageResult<CommunityDtos.Profile>> friends(@RequestParam(required=false) String keyword,@Valid PageQuery q){return ApiResponse.ok(access.friends(RequestContext.requireUserId(),keyword,q));}
    @PutMapping("/articles/{id}/visibility") public ApiResponse<ArticleAccessService.Policy> visibility(@PathVariable long id,@Valid @RequestBody ArticleAccessService.Change request){return ApiResponse.ok(access.change(RequestContext.requireUserId(),id,request));}
    @PostMapping("/articles/{id}/views") public ApiResponse<Map<String,Long>> views(@PathVariable long id){return ApiResponse.ok(access.recordView(RequestContext.requireUserId(),id));}
    @PutMapping("/articles/{id}/hidden") public ApiResponse<Void> hidden(@PathVariable long id,@Valid @RequestBody ArticleAccessService.Hidden request){access.hidden(RequestContext.requireUserId(),id,request.hidden());return ApiResponse.ok();}
}
