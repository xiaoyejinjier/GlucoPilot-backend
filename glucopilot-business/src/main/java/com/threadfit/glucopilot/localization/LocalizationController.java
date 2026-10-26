package com.threadfit.glucopilot.localization;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/localizations")
public class LocalizationController {
    private final LocalizationService service;
    public LocalizationController(LocalizationService service) { this.service=service; }
    @PostMapping("/en")
    public ApiResponse<List<LocalizationDtos.Result>> english(@Valid @RequestBody LocalizationDtos.Request request) {
        return ApiResponse.ok(service.localize(RequestContext.requireUserId(),request));
    }
}
