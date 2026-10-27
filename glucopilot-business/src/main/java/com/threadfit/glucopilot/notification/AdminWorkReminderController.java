package com.threadfit.glucopilot.notification;

import com.threadfit.glucopilot.auth.service.RuoYiAuthorizationService;
import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AdminWorkReminderController {
    private final AdminWorkReminderService service;
    private final RuoYiAuthorizationService authorization;

    @GetMapping("/admin/v1/work-reminders")
    @PreAuthorize("@ss.hasAnyPermi('content:news:list,feedback:manage,food:library:manage')")
    public ResponseEntity<ApiResponse<AdminWorkReminderService.Summary>> summary() {
        Long opsId=RequestContext.getOpsId();
        if(opsId==null)throw new BizException(ErrorCode.UNAUTHORIZED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.summary(authorization.permissionCodesOf(opsId))));
    }
}
