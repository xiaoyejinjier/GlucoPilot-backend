package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/community/tags")
public class AdminCommunityTagController {
    private final CommunityTagService service;
    public AdminCommunityTagController(CommunityTagService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<CommunityTagService.Row>> list(@RequestParam(required=false)String keyword,@RequestParam(required=false)String type,@RequestParam(required=false)Integer enabled,@Valid PageQuery page){RequestContext.requireOpsId();return ApiResponse.ok(service.list(keyword,type,enabled,page));}
    @PostMapping @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<CommunityTagService.Row> create(@Valid @RequestBody CommunityTagService.Change request){RequestContext.requireOpsId();return ApiResponse.ok(service.create(request));}
    @PutMapping("/{code}") @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<CommunityTagService.Row> update(@PathVariable String code,@Valid @RequestBody CommunityTagService.Change request){RequestContext.requireOpsId();return ApiResponse.ok(service.update(code,request));}
    @DeleteMapping("/{code}") @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> remove(@PathVariable String code){RequestContext.requireOpsId();service.remove(code);return ApiResponse.ok();}
}
