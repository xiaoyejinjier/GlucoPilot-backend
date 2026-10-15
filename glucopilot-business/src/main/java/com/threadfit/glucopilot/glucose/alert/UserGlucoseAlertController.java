package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class UserGlucoseAlertController {
    private final GlucoseAlertService service;

    @GetMapping("/api/v1/glucose-alerts/replies")
    public ApiResponse<PageResult<GlucoseAlertDtos.UserReply>> replies(@Valid PageQuery query) {
        return ApiResponse.ok(service.userReplies(RequestContext.requireUserId(),query));
    }
}
