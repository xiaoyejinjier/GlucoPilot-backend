package com.threadfit.glucopilot.community.notification.service;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.community.notification.mapper.CommunityEventMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityEventService {
    private final CommunityEventMapper mapper;

    public Long articleUser(long news) {
        var users = mapper.selectArticleAuthorUsers(news);
        return users.isEmpty() ? null : users.getFirst();
    }

    public void notifyEvent(
            long recipient, long actor, String kind, long target, Long news, Long comment) {
        if (recipient == actor) return;
        if (mapper.countActiveParticipants(recipient, actor) != 2) return;
        mapper.insertNotification(recipient, actor, kind, target, news, comment);
    }

    public void notifyAction(long actor, long news, String kind, boolean enabled) {
        if (!Set.of("LIKE", "COLLECT").contains(kind)) throw new IllegalArgumentException("kind");
        if (!enabled) {
            mapper.deleteActionNotification(actor, kind, news);
            return;
        }
        Long recipient = articleUser(news);
        if (recipient != null) notifyEvent(recipient, actor, kind, news, news, null);
    }
}
