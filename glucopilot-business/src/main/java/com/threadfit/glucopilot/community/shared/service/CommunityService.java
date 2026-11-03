package com.threadfit.glucopilot.community.shared.service;

import static com.threadfit.glucopilot.community.comment.dto.CommentDtos.*;
import static com.threadfit.glucopilot.community.message.dto.MessageDtos.*;
import static com.threadfit.glucopilot.community.profile.dto.ProfileDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.comment.service.CommunityCommentService;
import com.threadfit.glucopilot.community.follow.service.CommunityFollowService;
import com.threadfit.glucopilot.community.media.service.CommunityArticlePresentationService;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;
import com.threadfit.glucopilot.community.notification.service.CommunityEventService;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;
import com.threadfit.glucopilot.community.reaction.service.CommunityReactionService;
import com.threadfit.glucopilot.content.dto.ContentDtos;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityService {
    private final CommunityProfileService profiles;
    private final CommunityFollowService follows;
    private final CommunityReactionService reactions;
    private final CommunityCommentService comments;
    private final CommunityMessageService messages;
    private final CommunityArticlePresentationService presentation;
    private final CommunityEventService events;
    private final ArticleAccessService access;
    private final CommunityAccountCleanupService accountCleanup;

    /** 账号注销保留公开内容、互动计数和对端私信历史。 */
    public void purgeAccountData(long userId) {
        accountCleanup.purgeAccountData(userId);
    }

    /** 跨模块读取/协调入口；具体业务请进入message、comment、follow等功能目录。 */
    public void requireReadable(Long viewer, long newsId) {
        access.requireReadable(viewer, newsId);
    }

    public long viewCount(long newsId) {
        return access.views(newsId);
    }

    public Profile profile(Long viewer, String key) {
        return profiles.profile(viewer, key);
    }

    public Profile newsAuthor(Long viewer, long newsId) {
        return profiles.newsAuthor(viewer, newsId);
    }

    @Transactional
    public Profile follow(long viewer, String key, boolean desired) {
        return follows.follow(viewer, key, desired);
    }

    public PageResult<Profile> follows(long viewer, PageQuery q) {
        return follows.follows(viewer, q);
    }

    public PageResult<Profile> follows(long viewer, String keyword, PageQuery q) {
        return follows.follows(viewer, keyword, q);
    }

    public PageResult<Profile> search(long viewer, String keyword, PageQuery q) {
        return profiles.search(viewer, keyword, q);
    }

    public PageResult<ContentDtos.NewsListItem> collections(long viewer, PageQuery q) {
        return reactions.collections(viewer, q);
    }

    public PageResult<ContentDtos.NewsListItem> collections(
            long viewer, String keyword, PageQuery q) {
        return reactions.collections(viewer, keyword, q);
    }

    public PageResult<ContentDtos.NewsListItem> likes(long viewer, String keyword, PageQuery q) {
        return reactions.likes(viewer, keyword, q);
    }

    public long commentCount(long newsId) {
        return comments.commentCount(newsId);
    }

    public PageResult<Comment> comments(long viewer, long news, PageQuery q) {
        return comments.comments(viewer, news, q);
    }

    public PageResult<MyComment> myComments(long viewer, PageQuery q) {
        return comments.myComments(viewer, q);
    }

    public CommentContext commentContext(long viewer, long news, long targetId) {
        return comments.commentContext(viewer, news, targetId);
    }

    public Replies replies(long viewer, long news, long root, Long after, int size) {
        return comments.replies(viewer, news, root, after, size);
    }

    @Transactional
    public Comment addComment(long viewer, long news, CommentRequest request) {
        return comments.addComment(viewer, news, request);
    }

    @Transactional
    public void deleteComment(long viewer, long news, long comment) {
        comments.deleteComment(viewer, news, comment);
    }

    @Transactional
    public Message send(long viewer, long peer, MessageRequest request) {
        return messages.send(viewer, peer, request);
    }

    public Messages messages(long viewer, long peer, Long before, int size) {
        return messages.messages(viewer, peer, before, size);
    }

    @Transactional
    public void read(long viewer, long peer, String through) {
        messages.read(viewer, peer, through);
    }

    public PageResult<Conversation> inbox(long viewer, PageQuery q) {
        return messages.inbox(viewer, q);
    }

    public Unread unread(long viewer) {
        return messages.unread(viewer);
    }

    public List<PublicationDtos.ArticleImage> articleImages(long newsId) {
        return presentation.articleImages(newsId);
    }

    public List<PublicationDtos.ArticleImage> articleVideos(long newsId) {
        return presentation.articleVideos(newsId);
    }

    public List<PublicationDtos.Tag> articleTags(long newsId) {
        return presentation.articleTags(newsId);
    }

    public void notifyAction(long actor, long news, String kind, boolean enabled) {
        events.notifyAction(actor, news, kind, enabled);
    }

    public CommunityMessageService messageService() {
        return messages;
    }

    public CommunityCommentService commentService() {
        return comments;
    }

    public CommunityProfileService profileService() {
        return profiles;
    }

    public CommunityFollowService followService() {
        return follows;
    }

    public CommunityReactionService reactionService() {
        return reactions;
    }
}
