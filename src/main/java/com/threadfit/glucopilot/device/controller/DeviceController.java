package com.threadfit.glucopilot.device.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.device.adapter.MockDeviceAdapter;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备同步（Mock）。客户端不可伪造 source_type，由服务端固定为设备来源。
 */
@RestController
@RequestMapping("/api/v1/device")
@RequiredArgsConstructor
public class DeviceController {

    private final MockDeviceAdapter mockDeviceAdapter;

    @PostMapping("/glucose-sync")
    public ApiResponse<DeviceDtos.SyncResponse> sync(@Valid @RequestBody DeviceDtos.SyncRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mockDeviceAdapter.sync(userId, request));
    }
}
