package com.threadfit.glucopilot.device.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.service.UserDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端设备管理：列表、自助绑定、解绑。
 * 血糖同步仍走 {@code POST /api/v1/device/glucose-sync}。
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class UserDeviceController {

    private final UserDeviceService userDeviceService;

    @GetMapping
    public ApiResponse<List<DeviceDtos.DeviceItem>> listMine() {
        return ApiResponse.ok(userDeviceService.listMine(RequestContext.requireUserId()));
    }

    @PostMapping("/bind")
    public ApiResponse<DeviceDtos.DeviceItem> bind(@Valid @RequestBody DeviceDtos.BindRequest request) {
        return ApiResponse.ok(userDeviceService.bind(RequestContext.requireUserId(), request));
    }

    @PostMapping("/{id:\\d+}/unbind")
    public ApiResponse<Void> unbind(@PathVariable Long id) {
        userDeviceService.unbind(RequestContext.requireUserId(), id);
        return ApiResponse.ok();
    }
}
