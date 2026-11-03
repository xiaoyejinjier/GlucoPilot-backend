package com.threadfit.glucopilot.community.message.mapper;

/** Validated filters shared by count and page SQL. */
public record AdminMessageQuery(
        String keyword,
        Long senderId,
        Long recipientId,
        String readState,
        Long firstUserId,
        Long secondUserId) {}
