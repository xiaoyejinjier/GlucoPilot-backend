package com.threadfit.glucopilot.community.follow.service;

import static com.threadfit.glucopilot.community.profile.dto.ProfileDtos.*;
import static com.threadfit.glucopilot.community.shared.support.CommunityValidation.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.follow.mapper.CommunityFollowMapper;
import com.threadfit.glucopilot.community.notification.service.CommunityEventService;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityFollowService {
    private final CommunityFollowMapper mapper;
    private final CommunityProfileService profiles;
    private final CommunityEventService events;

    @Transactional
    public Profile follow(long viewer, String key, boolean desired) {
        if (mapper.lockActiveUser(viewer).isEmpty()) throw missing();
        Profile target = profiles.profile(viewer, key);
        if (target.isSelf()) throw new BizException(ErrorCode.BAD_REQUEST, "不能关注自己");
        String canonical = target.key();
        mapper.upsertFollowing(viewer, canonical, desired ? 1 : 0, desired ? 1 : 0);
        if (target.userId() != null)
            mapper.clearBloggerAliases(viewer, Long.parseLong(target.userId()));
        if (target.userId() != null && target.followed() != desired) {
            long recipient = Long.parseLong(target.userId());
            if (desired) events.notifyEvent(recipient, viewer, "FOLLOW", recipient, null, null);
            else mapper.deleteFollowNotification(recipient, viewer);
        }
        return profiles.profile(viewer, key);
    }

    public PageResult<Profile> follows(long viewer, PageQuery q) {
        return follows(viewer, null, q);
    }

    public PageResult<Profile> follows(long viewer, String keyword, PageQuery q) {
        profiles.requireUser(viewer);
        page(q);
        String term = listKeyword(keyword, 32);
        long total = mapper.countFollowing(viewer, term);
        var keys = mapper.selectFollowingKeys(viewer, term, q.getPageSize(), q.offset());
        return PageResult.of(
                q.getPageNum(),
                q.getPageSize(),
                total,
                keys.stream().map(key -> profiles.profile(viewer, key)).toList());
    }
}
