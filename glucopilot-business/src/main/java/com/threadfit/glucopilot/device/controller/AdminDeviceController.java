package com.threadfit.glucopilot.device.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.device.dto.AdminDeviceDtos;
import com.threadfit.glucopilot.device.service.AdminDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端设备档案：CRUD、同步流水、云授权。 */
@RestController
@RequestMapping("/admin/v1/devices")
@RequiredArgsConstructor
public class AdminDeviceController {

    private final AdminDeviceService adminDeviceService;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<PageResult<AdminDeviceDtos.DeviceItem>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer deviceStatus,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String deviceCategory,
            @RequestParam(required = false) Boolean bound,
            @RequestParam(required = false) Boolean cloudLinked,
            @Valid PageQuery query) {
        return ApiResponse.ok(adminDeviceService.page(
                keyword, deviceStatus, provider, deviceCategory, bound, cloudLinked, query));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<AdminDeviceDtos.DeviceItem> create(@Valid @RequestBody AdminDeviceDtos.CreateRequest request) {
        return ApiResponse.ok(adminDeviceService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<AdminDeviceDtos.DeviceItem> update(
            @PathVariable Long id, @Valid @RequestBody AdminDeviceDtos.UpdateRequest request) {
        return ApiResponse.ok(adminDeviceService.update(id, request));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<AdminDeviceDtos.UpdatedResult> updateStatus(
            @PathVariable Long id, @Valid @RequestBody AdminDeviceDtos.StatusRequest request) {
        return ApiResponse.ok(adminDeviceService.updateStatus(id, request.getDeviceStatus()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        adminDeviceService.remove(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id:\\d+}/sync-logs")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<List<AdminDeviceDtos.SyncLogItem>> syncLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(adminDeviceService.listSyncLogs(id, limit));
    }

    @GetMapping("/{id:\\d+}/sync-logs/{logId:\\d+}/samples")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<AdminDeviceDtos.SyncSamplesResponse> syncSamples(
            @PathVariable Long id, @PathVariable Long logId) {
        return ApiResponse.ok(adminDeviceService.listSyncSamples(id, logId));
    }

    @GetMapping("/{id:\\d+}/cloud-auth")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<AdminDeviceDtos.CloudAuthStatus> cloudAuth(@PathVariable Long id) {
        return ApiResponse.ok(adminDeviceService.cloudAuth(id));
    }

    @DeleteMapping("/{id:\\d+}/cloud-auth")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DEVICE_MANAGE + "')")
    public ApiResponse<Void> revokeCloudAuth(@PathVariable Long id) {
        adminDeviceService.revokeCloudAuth(id);
        return ApiResponse.ok();
    }
}
