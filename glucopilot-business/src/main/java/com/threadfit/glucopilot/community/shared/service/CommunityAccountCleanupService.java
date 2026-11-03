package com.threadfit.glucopilot.community.shared.service;

import com.threadfit.glucopilot.community.shared.mapper.CommunityAccountCleanupMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注销时清除本人社区偏好并匿名化博主，保留其他用户需要的公开内容和私信历史。 */
@Service
@RequiredArgsConstructor
public class CommunityAccountCleanupService {
    private final CommunityAccountCleanupMapper mapper;

    @Transactional
    public void purgeAccountData(long userId) {
        mapper.deleteFollows(userId);
        mapper.deleteReaders(userId);
        mapper.deleteViews(userId);
        mapper.deleteHiddenArticles(userId);
        mapper.deleteDrafts(userId);
        mapper.deleteNotifications(userId);
        mapper.deleteReports(userId);
        mapper.anonymizeBlogger(userId);
    }
}
