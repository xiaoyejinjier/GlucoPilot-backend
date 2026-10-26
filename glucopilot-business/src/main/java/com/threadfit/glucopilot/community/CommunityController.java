package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import static com.threadfit.glucopilot.community.CommunityDtos.*;

@RestController
@RequestMapping("/api/v1")
public class CommunityController {
    private final CommunityService service;
    public CommunityController(CommunityService service) { this.service=service; }
    private long user() { return RequestContext.requireUserId(); }
    @GetMapping("/community/profiles")
    public ApiResponse<PageResult<Profile>> search(@RequestParam String keyword,@Valid PageQuery q) { return ApiResponse.ok(service.search(user(),keyword,q)); }
    @GetMapping("/community/profiles/{key}")
    public ApiResponse<Profile> profile(@PathVariable String key) { long u=user();return ApiResponse.ok(service.profile(u,key)); }
    @PutMapping("/community/profiles/{key}/following")
    public ApiResponse<Profile> follow(@PathVariable String key,@Valid @RequestBody Following request) { return ApiResponse.ok(service.follow(user(),key,request.following())); }
    @GetMapping("/community/following")
    public ApiResponse<PageResult<Profile>> following(@RequestParam(required=false) String keyword,@Valid PageQuery q) { return ApiResponse.ok(service.follows(user(),keyword,q)); }
    @GetMapping("/community/collections")
    public ApiResponse<PageResult<com.threadfit.glucopilot.content.dto.ContentDtos.NewsListItem>> collections(@RequestParam(required=false) String keyword,@Valid PageQuery q) { return ApiResponse.ok(service.collections(user(),keyword,q)); }
    @GetMapping("/news/{id}/comments")
    public ApiResponse<PageResult<Comment>> comments(@PathVariable long id,@Valid PageQuery q) { return ApiResponse.ok(service.comments(user(),id,q)); }

    /**
     * 「我的评论」：我发出的评论与回复（含被 AI 隐藏的），新的在前。
     * <p>
     * 之前 C 端只有 {@code /news/{id}/comments}，任何评论操作都要先知道文章 id，
     * 用户发完评论就再也找不回自己说过什么；被机器隐藏时更是完全无从得知。
     */
    @GetMapping("/community/comments/mine")
    public ApiResponse<PageResult<MyComment>> myComments(@Valid PageQuery q) { return ApiResponse.ok(service.myComments(user(),q)); }
    @GetMapping("/news/{id}/comments/{commentId}/context")
    public ApiResponse<CommentContext> commentContext(@PathVariable long id,@PathVariable long commentId) { return ApiResponse.ok(service.commentContext(user(),id,commentId)); }
    @PostMapping("/news/{id}/comments")
    public ApiResponse<Comment> comment(@PathVariable long id,@Valid @RequestBody CommentRequest request) { return ApiResponse.ok(service.addComment(user(),id,request)); }
    @PostMapping("/news/{id}/comments/{targetId}/replies")
    public ApiResponse<Comment> reply(@PathVariable long id,@PathVariable long targetId,@Valid @RequestBody CommentRequest request) {
        return ApiResponse.ok(service.addComment(user(),id,new CommentRequest(request.content(),request.clientRequestId(),targetId)));
    }
    @GetMapping("/news/{id}/comments/{rootId}/replies")
    public ApiResponse<Replies> replies(@PathVariable long id,@PathVariable long rootId,@RequestParam(required=false) Long afterId,@RequestParam(defaultValue="10") int pageSize) {
        return ApiResponse.ok(service.replies(user(),id,rootId,afterId,pageSize));
    }
    @DeleteMapping("/news/{id}/comments/{commentId}")
    public ApiResponse<Void> delete(@PathVariable long id,@PathVariable long commentId) { service.deleteComment(user(),id,commentId);return ApiResponse.ok(); }
    @GetMapping("/community/messages")
    public ApiResponse<PageResult<Conversation>> inbox(@Valid PageQuery q) { return ApiResponse.ok(service.inbox(user(),q)); }
    @GetMapping("/community/messages/unread")
    public ApiResponse<Unread> unread() { return ApiResponse.ok(service.unread(user())); }
    @GetMapping("/community/messages/{peerUserId}")
    public ApiResponse<Messages> messages(@PathVariable long peerUserId,@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="20") int pageSize) { return ApiResponse.ok(service.messages(user(),peerUserId,beforeId,pageSize)); }
    @PostMapping("/community/messages/{peerUserId}")
    public ApiResponse<Message> send(@PathVariable long peerUserId,@Valid @RequestBody MessageRequest request) { return ApiResponse.ok(service.send(user(),peerUserId,request)); }
    @PutMapping("/community/messages/{peerUserId}/read")
    public ApiResponse<Void> read(@PathVariable long peerUserId,@Valid @RequestBody ReadRequest request) { service.read(user(),peerUserId,request.throughId());return ApiResponse.ok(); }
}
