package com.threadfit.glucopilot.device.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.service.DeviceSyncFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备血糖同步：按 provider 路由到厂商适配器，统一写入血糖。
 */
@RestController
@RequestMapping("/api/v1/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceSyncFacade deviceSyncFacade;

    @PostMapping("/glucose-sync")
    public ApiResponse<DeviceDtos.SyncResponse> sync(@Valid @RequestBody DeviceDtos.SyncRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(deviceSyncFacade.sync(userId, request));
    }
}
