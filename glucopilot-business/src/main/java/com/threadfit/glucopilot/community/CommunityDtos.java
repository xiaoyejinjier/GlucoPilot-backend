package com.threadfit.glucopilot.community;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class CommunityDtos {
    private CommunityDtos() {}
    public record Profile(String key, String userId, String nickname, String avatarUrl, String bio,
                          boolean followed, boolean isSelf) {}
    public record Following(@NotNull Boolean following) {}
    public record CommentRequest(@NotBlank @Size(max=500) String content,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String clientRequestId,
            @Positive Long replyToCommentId) {
        public CommentRequest(String content,String clientRequestId) { this(content,clientRequestId,null); }
    }
    public record MessageRequest(@NotBlank @Size(max=1000) String content,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String clientRequestId) {}
    public record ReadRequest(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String throughId) {}
    public record Comment(String id, String content, LocalDateTime createdAt, Profile author, boolean mine,
                          String rootCommentId,String replyToCommentId,Profile replyToAuthor,
                          List<Comment> replies,long replyCount,boolean replyHasMore,String replyNextAfterId) {}
    public record Replies(List<Comment> records,boolean hasMore,String nextAfterId) {}
    public record Message(String id, String senderId, String recipientId, String content,
                          LocalDateTime createdAt, LocalDateTime readAt, boolean mine) {}
    public record Messages(List<Message> records, boolean hasMore, String nextBeforeId) {}
    public record Conversation(Profile peer, Message lastMessage, long unreadCount) {}
    public record Unread(long unreadCount, String latestMessageId, String latestSenderId) {}
}
