package com.threadfit.glucopilot.community.message.realtime;

import com.threadfit.glucopilot.auth.dto.AuthDtos.SessionPrincipal;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.*;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunityMessageSocketTest {
    AuthService auth;
    CommunityMessageService messages;
    JsonUtils json;
    CommunityMessageSocket handler;
    ValidatorFactory validation;
    final Map<String, List<String>> frames = new HashMap<>();

    @BeforeEach
    void setup() {
        auth = mock(AuthService.class);
        messages = mock(CommunityMessageService.class);
        json = new JsonUtils(new ObjectMapper());
        validation = Validation.buildDefaultValidatorFactory();
        handler = new CommunityMessageSocket(auth, messages, json, validation.getValidator());
        when(auth.resolveSession("first")).thenReturn(new SessionPrincipal(1L, 1));
        when(auth.resolveSession("second")).thenReturn(new SessionPrincipal(2L, 1));
        when(auth.resolveSession("third")).thenReturn(new SessionPrincipal(3L, 1));
    }

    @AfterEach void cleanup() { validation.close(); }

    WebSocketSession connect(String id, long user, String token) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(MessageSocketHandshake.USER, user, MessageSocketHandshake.TOKEN, token));
        frames.put(id, new ArrayList<>());
        doAnswer(invocation -> { frames.get(id).add(((TextMessage) invocation.getArgument(0)).getPayload()); return null; })
                .when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);
        return session;
    }

    Map<?, ?> last(String id) { return json.fromJson(frames.get(id).getLast(), Map.class); }
    Map<?, ?> data(String id) { return (Map<?, ?>) last(id).get("data"); }
    void send(WebSocketSession socket, String payload) throws Exception { handler.handleMessage(socket, new TextMessage(payload)); }

    @Test void handshakeBindsBearerIdentityAndRejectsMissingOrDisabledSessions() throws Exception {
        var handshake = new MessageSocketHandshake(auth, json);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer first");
        var attributes = new HashMap<String, Object>();
        assertTrue(handshake.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()), handler, attributes));
        assertEquals(1L, attributes.get(MessageSocketHandshake.USER));
        var missing = new MockHttpServletResponse();
        assertFalse(handshake.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest()), new ServletServerHttpResponse(missing), handler, new HashMap<>()));
        assertEquals(401, missing.getStatus());
        when(auth.resolveSession("first")).thenReturn(new SessionPrincipal(1L, 0));
        var disabled = new MockHttpServletResponse();
        assertFalse(handshake.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(disabled), handler, new HashMap<>()));
        assertEquals(403, disabled.getStatus());
    }

    @Test void readyHeartbeatAndSendUseServerIdentityAndPersistedAcknowledgement() throws Exception {
        var first = connect("a", 1, "first");
        assertEquals("READY", data("a").get("type"));
        send(first, "{\"type\":\"PING\"}");
        assertEquals("PONG", data("a").get("type"));
        var saved = new Message("18", "1", "2", "你好", null, null, true);
        when(messages.send(eq(1L), eq(2L), any())).thenReturn(saved);
        send(first, "{\"type\":\"SEND\",\"peerUserId\":\"2\",\"content\":\"你好\",\"clientRequestId\":\"request_18\"}");
        verify(messages).send(1, 2, new MessageRequest("你好", "request_18"));
        assertEquals(0, last("a").get("code"));
        assertEquals("SENT", data("a").get("type"));
        assertEquals("18", ((Map<?, ?>) data("a").get("message")).get("id"));
        assertNotNull(last("a").get("requestId"));
    }

    @Test void invalidFramesCannotReachService() throws Exception {
        var first = connect("a", 1, "first");
        for (String payload : List.of("not-json", "null", "{}",
                "{\"type\":\"SEND\",\"peerUserId\":\"-2\"}",
                "{\"type\":\"SEND\",\"peerUserId\":\"9999999999999999999\"}",
                "{\"type\":\"SEND\",\"peerUserId\":\"2\",\"content\":\"x\",\"clientRequestId\":\"short\"}")) {
            send(first, payload);
            assertEquals(40000, last("a").get("code"));
        }
        verifyNoInteractions(messages);
    }

    @Test void providedSenderCannotOverrideBoundIdentity() throws Exception {
        var first = connect("a", 1, "first");
        when(messages.send(eq(1L), eq(2L), any())).thenReturn(new Message("18", "1", "2", "x", null, null, true));
        send(first, "{\"type\":\"SEND\",\"senderId\":\"99\",\"peerUserId\":\"2\",\"content\":\"x\",\"clientRequestId\":\"request_18\"}");
        verify(messages).send(eq(1L), eq(2L), any());
        verify(messages, never()).send(eq(99L), anyLong(), any());
    }

    @Test void businessRejectionIsCorrelatedAndKeepsSocketAvailable() throws Exception {
        var first = connect("a", 1, "first");
        when(messages.send(anyLong(), anyLong(), any())).thenThrow(new BizException(ErrorCode.MESSAGE_REQUEST_PENDING));
        send(first, "{\"type\":\"SEND\",\"peerUserId\":\"2\",\"content\":\"你好\",\"clientRequestId\":\"request_18\"}");
        assertEquals(40301, last("a").get("code"));
        assertEquals("request_18", data("a").get("clientRequestId"));
        verify(first, never()).close(any());
    }

    @Test void onlyParticipantsReceiveChangesAndClosedConnectionsAreRemoved() throws Exception {
        var first = connect("a", 1, "first");
        connect("b", 2, "second"); connect("c", 3, "third");
        handler.changed(new DirectMessageChanged(1, 2, "19", "MESSAGE"));
        assertEquals("2", data("a").get("peerUserId"));
        assertEquals("1", data("b").get("peerUserId"));
        assertEquals("READY", data("c").get("type"));
        assertFalse(frames.get("a").getLast().contains("content"));
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        int size = frames.get("a").size();
        handler.changed(new DirectMessageChanged(1, 2, "19", "READ"));
        assertEquals(size, frames.get("a").size());
        assertEquals("READ", data("b").get("kind"));
    }

    @Test void expiredOrReplacedSessionCannotSendOrReceive() throws Exception {
        var first = connect("a", 1, "first");
        when(auth.resolveSession("first")).thenThrow(new BizException(ErrorCode.SESSION_REPLACED));
        send(first, "{\"type\":\"PING\"}");
        assertEquals(40101, last("a").get("code"));
        verify(first).close(argThat(status -> status.getCode() == 4401));
        verifyNoInteractions(messages);
        var second = connect("b", 2, "second");
        when(auth.resolveSession("second")).thenReturn(null);
        handler.changed(new DirectMessageChanged(1, 2, "19", "MESSAGE"));
        assertEquals(40100, last("b").get("code"));
        verify(second).close(argThat(status -> status.getCode() == 4401));
    }

    @Test void forgedHandshakeUserAndMissingAttributesAreClosed() throws Exception {
        var forged = connect("a", 2, "first");
        verify(forged).close(argThat(status -> status.getCode() == 4401));
        var empty = mock(WebSocketSession.class);
        when(empty.getAttributes()).thenReturn(Map.of());
        handler.afterConnectionEstablished(empty);
        verify(empty).close(argThat(status -> status.getCode() == 4401));
    }
}
