package com.threadfit.glucopilot.community.message.realtime;

import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MessageSocketHandshake implements HandshakeInterceptor {
    static final String TOKEN = MessageSocketHandshake.class.getName() + ".token";
    static final String USER = MessageSocketHandshake.class.getName() + ".user";
    private final AuthService auth;
    private final JsonUtils json;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) throws Exception {
        try {
            String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
            var principal = auth.resolveSession(token);
            if (principal == null) throw new BizException(ErrorCode.UNAUTHORIZED);
            if (!Integer.valueOf(1).equals(principal.accountStatus()))
                throw new BizException(ErrorCode.FORBIDDEN);
            attributes.put(TOKEN, token);
            attributes.put(USER, principal.userId());
            return true;
        } catch (BizException error) {
            response.setStatusCode(error.getErrorCode().getHttpStatus());
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            response.getBody().write(json.toJson(ApiResponse.fail(error.getErrorCode(), error.getMessage()))
                    .getBytes(StandardCharsets.UTF_8));
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {}
}
