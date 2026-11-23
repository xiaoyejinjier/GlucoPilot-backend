package com.threadfit.glucopilot.community.message.realtime;

import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.MessageRequest;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 原生私信协议：身份来自握手会话；SEND 复用现有事务服务，提交后才确认成功。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityMessageSocket extends TextWebSocketHandler {
    private final AuthService auth;
    private final CommunityMessageService messages;
    private final JsonUtils json;
    private final Validator validator;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public record Command(String type, String peerUserId, String content, String clientRequestId) {}

    private static final class Connection {
        final WebSocketSession socket;
        final long user;
        final String token;
        volatile long lastSeen = System.nanoTime();
        Connection(WebSocketSession socket, long user, String token) {
            // Redis通知、发送确认、心跳可能同时写同一个连接，统一串行写出并限制积压。
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 64 * 1024);
            this.user = user;
            this.token = token;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object user = session.getAttributes().get(MessageSocketHandshake.USER);
        Object token = session.getAttributes().get(MessageSocketHandshake.TOKEN);
        if (!(user instanceof Long id) || !(token instanceof String accessToken)) {
            session.close(new CloseStatus(4401, "Unauthorized"));
            return;
        }
        var connection = new Connection(session, id, accessToken);
        // 防止异常客户端重复建连接；正常小程序前台只保持一个。
        synchronized (connections) {
            if (connections.values().stream().filter(item -> item.user == id).count() >= 5) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            connections.put(session.getId(), connection);
        }
        try {
            authorize(connection);
            write(connection, ErrorCode.SUCCESS, "ok", Map.of("type", "READY"));
        } catch (BizException error) { rejectSession(connection, error); }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage frame) {
        var connection = connections.get(session.getId());
        if (connection == null) return;
        String requestId = "";
        try {
            authorize(connection); // 长连接不能只校验握手：旧终端被踢后也不能继续收发。
            connection.lastSeen = System.nanoTime();
            if (frame.getPayloadLength() > 8192) throw new BizException(ErrorCode.BAD_REQUEST);
            Command command = json.fromJson(frame.getPayload(), Command.class);
            if (command == null) throw new BizException(ErrorCode.BAD_REQUEST);
            if (command.clientRequestId() != null && command.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))
                requestId = command.clientRequestId();
            if ("PING".equals(command.type())) {
                write(connection, ErrorCode.SUCCESS, "ok", Map.of("type", "PONG"));
                return;
            }
            if (!"SEND".equals(command.type()) || command.peerUserId() == null
                    || !command.peerUserId().matches("[1-9][0-9]{0,18}"))
                throw new BizException(ErrorCode.BAD_REQUEST);
            long peer;
            try { peer = Long.parseLong(command.peerUserId()); }
            catch (NumberFormatException error) { throw new BizException(ErrorCode.BAD_REQUEST); }
            var request = new MessageRequest(command.content(), command.clientRequestId());
            if (!validator.validate(request).isEmpty()) throw new BizException(ErrorCode.BAD_REQUEST);
            var saved = messages.send(connection.user, peer, request);
            write(connection, ErrorCode.SUCCESS, "ok", Map.of(
                    "type", "SENT", "clientRequestId", request.clientRequestId(), "message", saved));
        } catch (BizException error) {
            if (error.getErrorCode() == ErrorCode.UNAUTHORIZED || error.getErrorCode() == ErrorCode.SESSION_REPLACED
                    || error.getErrorCode() == ErrorCode.FORBIDDEN) rejectSession(connection, error);
            else write(connection, error.getErrorCode(), error.getMessage(),
                    Map.of("type", "ERROR", "clientRequestId", requestId));
        } catch (Exception error) {
            // 不记录正文、令牌、密钥，也不把内部异常信息返回客户端。
            log.warn("Private message socket failed type={}", error.getClass().getSimpleName());
            write(connection, ErrorCode.INTERNAL_ERROR, "消息处理失败，请重试",
                    Map.of("type", "ERROR", "clientRequestId", requestId));
        }
    }

    private void authorize(Connection connection) {
        var principal = auth.resolveSession(connection.token);
        if (principal == null || principal.userId() != connection.user)
            throw new BizException(ErrorCode.UNAUTHORIZED);
        if (!Integer.valueOf(1).equals(principal.accountStatus())) throw new BizException(ErrorCode.FORBIDDEN);
    }

    /** 推送仅含变化提示；双方随后按各自身份读取数据库，避免转发第三方消息内容。 */
    public void changed(DirectMessageChanged event) {
        for (var connection : connections.values()) {
            if (connection.user != event.senderId() && connection.user != event.recipientId()) continue;
            try {
                authorize(connection);
                long peer = connection.user == event.senderId() ? event.recipientId() : event.senderId();
                write(connection, ErrorCode.SUCCESS, "ok", Map.of("type", "DM_CHANGED",
                        "peerUserId", Long.toString(peer), "messageId", event.messageId(), "kind", event.kind()));
            } catch (BizException error) { rejectSession(connection, error); }
            catch (Exception error) { close(connection, CloseStatus.SERVER_ERROR); }
        }
    }

    private void rejectSession(Connection connection, BizException error) {
        write(connection, error.getErrorCode(), error.getMessage(), Map.of("type", "ERROR"));
        close(connection, new CloseStatus(4401, "Session unavailable"));
    }

    private void write(Connection connection, ErrorCode code, String message, Object data) {
        try {
            var response = new ApiResponse<>(code.getCode(), message, data, UUID.randomUUID().toString());
            if (connection.socket.isOpen()) connection.socket.sendMessage(new TextMessage(json.toJson(response)));
        } catch (Exception error) { close(connection, CloseStatus.SERVER_ERROR); }
    }

    private void close(Connection connection, CloseStatus status) {
        connections.remove(connection.socket.getId(), connection);
        try { connection.socket.close(status); } catch (IOException ignored) {}
    }

    @Scheduled(fixedDelay = 30000)
    public void closeIdleConnections() {
        long now = System.nanoTime();
        for (var connection : connections.values())
            if (now - connection.lastSeen > 75_000_000_000L) close(connection, CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) throws Exception {
        var connection = connections.get(session.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    }
}
