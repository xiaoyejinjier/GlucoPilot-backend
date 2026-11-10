package com.threadfit.glucopilot.notification.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.notification.dto.UserReminderDtos;
import com.threadfit.glucopilot.notification.service.UserReminderService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/user-reminders")
@RequiredArgsConstructor
public class UserReminderController {
    private final UserReminderService service;

    @GetMapping
    public ApiResponse<UserReminderDtos.Result> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @Valid PageQuery page) {
        return ApiResponse.ok(
                service.list(
                        RequestContext.requireUserId(),
                        date == null ? LocalDate.now() : date,
                        page));
    }

    @PutMapping("/read-all")
    public ApiResponse<Void> readAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        service.readAll(RequestContext.requireUserId(), date == null ? LocalDate.now() : date);
        return ApiResponse.ok();
    }

    @GetMapping("/{kind}/{id}")
    public ApiResponse<UserReminderDtos.Item> detail(
            @PathVariable String kind, @PathVariable long id) {
        return ApiResponse.ok(service.detail(RequestContext.requireUserId(), kind, id));
    }

    @PutMapping("/{kind}/{id}/read")
    public ApiResponse<Void> read(
            @PathVariable String kind,
            @PathVariable long id,
            @Valid @RequestBody UserReminderDtos.Read request) {
        service.read(RequestContext.requireUserId(), kind, id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/information/{kind}/read")
    public ApiResponse<Void> readInformation(@PathVariable String kind) {
        service.readInformation(RequestContext.requireUserId(), kind);
        return ApiResponse.ok();
    }
}
