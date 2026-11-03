package com.threadfit.glucopilot.community.draft.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.draft.dto.CommunityDraftDtos;
import com.threadfit.glucopilot.community.draft.service.CommunityDraftService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community/drafts")
public class CommunityDraftController {
    private final CommunityDraftService service;

    @GetMapping
    public ApiResponse<PageResult<CommunityDraftDtos.Draft>> list(@Valid PageQuery q) {
        return ApiResponse.ok(service.list(RequestContext.requireUserId(), q));
    }

    @GetMapping("/{id}")
    public ApiResponse<CommunityDraftDtos.Draft> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(RequestContext.requireUserId(), id));
    }

    @PostMapping
    public ApiResponse<CommunityDraftDtos.Draft> create(@RequestBody CommunityDraftDtos.Payload p) {
        return ApiResponse.ok(service.create(RequestContext.requireUserId(), p));
    }

    @PutMapping("/{id}")
    public ApiResponse<CommunityDraftDtos.Draft> edit(
            @PathVariable long id, @RequestBody CommunityDraftDtos.Payload p) {
        return ApiResponse.ok(service.edit(RequestContext.requireUserId(), id, p));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam int revision) {
        service.delete(RequestContext.requireUserId(), id, revision);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<PublicationDtos.Article> publish(
            @PathVariable long id, @RequestBody CommunityDraftDtos.Publish p) {
        return ApiResponse.ok(service.publish(RequestContext.requireUserId(), id, p));
    }
}
