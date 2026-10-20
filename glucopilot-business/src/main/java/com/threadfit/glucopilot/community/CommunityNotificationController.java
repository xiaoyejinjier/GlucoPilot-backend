package com.threadfit.glucopilot.community;
import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/community")
public class CommunityNotificationController {
    private final CommunityNotificationService service;
    @GetMapping("/information/summary") public ApiResponse<CommunityNotificationService.Summary> summary(){return ApiResponse.ok(service.summary(RequestContext.requireUserId()));}
    @GetMapping("/notifications") public ApiResponse<PageResult<CommunityNotificationService.Notice>> list(@RequestParam String kind,@Valid PageQuery q){return ApiResponse.ok(service.list(RequestContext.requireUserId(),kind,q));}
    @PutMapping("/notifications/read") public ApiResponse<Void> read(@RequestBody CommunityNotificationService.Read p){service.read(RequestContext.requireUserId(),p);return ApiResponse.ok();}
}
