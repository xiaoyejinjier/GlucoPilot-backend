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
    public record CommentContext(String commentId,Comment root) {}
    /**
     * 「我的评论」列表项：我自己发出去的评论/回复，含已被 AI 隐藏的那些。
     * <p>
     * <b>为什么隐藏的也要返回</b>：评论是先发后审，机器判违规会立即从公开列表消失。
     * 若作者这边也完全看不到，他会以为"我明明发了却没了"，只能反复重发或来投诉；
     * 所以这里返回 {@code hidden=true} 并让前端标注「已被系统隐藏」，既诚实又不暴露判定细节。
     * <p>
     * {@code articleAvailable=false} 表示文章已删除或已下架：这条评论仍属于用户的历史，
     * 不隐藏掉，但前端不要跳转（跳过去只会 404）。
     */
    public record MyComment(String id, long newsId, String articleTitle, boolean articleAvailable,
                            String content, LocalDateTime createdAt,
                            String rootCommentId, String replyToCommentId,
                            boolean hidden, long replyCount, long likeCount) {}
    public record Message(String id, String senderId, String recipientId, String content,
                          LocalDateTime createdAt, LocalDateTime readAt, boolean mine) {}
    public record MessagePermission(boolean canSend,boolean following,boolean mutualFollowing,String state) {}
    public record Messages(List<Message> records, boolean hasMore, String nextBeforeId,MessagePermission sendPermission) {}
    public record Conversation(Profile peer, Message lastMessage, long unreadCount) {}
    public record Unread(long unreadCount, String latestMessageId, String latestSenderId) {}
}
