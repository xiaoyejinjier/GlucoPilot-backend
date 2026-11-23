package com.threadfit.glucopilot.community.message.realtime;

import com.threadfit.glucopilot.auth.dto.AuthDtos.SessionPrincipal;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.community.message.controller.CommunityMessageController;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.Message;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真正的HTTP Upgrade、Tomcat WebSocket与JDK客户端联通；不连接业务库/Redis、不使用真实账号。 */
class MessageWebSocketNetworkTest {
    @TempDir Path temporary;
    Tomcat tomcat;
    AnnotationConfigWebApplicationContext spring;
    HttpClient client;
    String address;

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(MessageWebSocketConfig.class)
    static class Setup {
        @Bean AuthService auth() {
            var service = mock(AuthService.class);
            when(service.resolveSession("network-test")).thenReturn(new SessionPrincipal(1L, 1));
            return service;
        }
        @Bean CommunityMessageService messages() {
            var service = mock(CommunityMessageService.class);
            when(service.send(eq(1L), eq(2L), any())).thenReturn(new Message("42", "1", "2", "你好", null, null, true));
            return service;
        }
        @Bean JsonUtils json() { return new JsonUtils(new ObjectMapper()); }
        @Bean CommunityMessageSocket socket(AuthService auth, CommunityMessageService messages, JsonUtils json, Validator validator) {
            return new CommunityMessageSocket(auth, messages, json, validator);
        }
        @Bean MessageSocketHandshake handshake(AuthService auth, JsonUtils json) { return new MessageSocketHandshake(auth, json); }
        @Bean CommunityMessageController controller(CommunityMessageService messages) { return new CommunityMessageController(messages); }
    }

    @BeforeEach void start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(temporary.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", temporary.toString());
        context.addServletContainerInitializer(new WsSci(), null);
        spring = new AnnotationConfigWebApplicationContext();
        spring.register(Setup.class);
        var servlet = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(spring));
        servlet.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");
        tomcat.start();
        address = "ws://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/api/v1/community/messages/socket";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void stop() throws Exception {
        if (client != null) client.close();
        if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
        if (spring != null) spring.close();
    }

    @Test void authenticatedUpgradeDoesNotHitPeerIdControllerAndCanExchangeMessages() throws Exception {
        var frames = new LinkedBlockingQueue<String>();
        var socket = client.newWebSocketBuilder().header("Authorization", "Bearer network-test")
                .header("Origin", "https://servicewechat.com")
                .buildAsync(URI.create(address), listener(frames)).get(10, TimeUnit.SECONDS);
        assertTrue(frames.poll(5, TimeUnit.SECONDS).contains("READY"));
        socket.sendText("{\"type\":\"PING\"}", true).get(5, TimeUnit.SECONDS);
        assertTrue(frames.poll(5, TimeUnit.SECONDS).contains("PONG"));
        socket.sendText("{\"type\":\"SEND\",\"peerUserId\":\"2\",\"content\":\"你好\",\"clientRequestId\":\"network-request\"}", true).get(5, TimeUnit.SECONDS);
        String ack = frames.poll(5, TimeUnit.SECONDS);
        assertTrue(ack.contains("SENT"));
        assertTrue(ack.contains("\"id\":\"42\""));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test void unauthenticatedUpgradeFailsWith401() {
        var error = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder()
                .buildAsync(URI.create(address), listener(new LinkedBlockingQueue<>())).get(10, TimeUnit.SECONDS));
        Throwable cause = error.getCause();
        while (cause != null && !(cause instanceof WebSocketHandshakeException)) cause = cause.getCause();
        assertInstanceOf(WebSocketHandshakeException.class, cause);
        assertEquals(401, ((WebSocketHandshakeException) cause).getResponse().statusCode());
    }

    @Test void unrelatedBrowserOriginIsNotAllowedEvenWithToken() {
        var error = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder()
                .header("Authorization", "Bearer network-test").header("Origin", "https://unrelated.test")
                .buildAsync(URI.create(address), listener(new LinkedBlockingQueue<>())).get(10, TimeUnit.SECONDS));
        Throwable cause = error.getCause();
        while (cause != null && !(cause instanceof WebSocketHandshakeException)) cause = cause.getCause();
        assertInstanceOf(WebSocketHandshakeException.class, cause);
        assertEquals(403, ((WebSocketHandshakeException) cause).getResponse().statusCode());
    }

    private WebSocket.Listener listener(BlockingQueue<String> frames) {
        return new WebSocket.Listener() {
            final StringBuilder text = new StringBuilder();
            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
                text.append(part);
                if (last) { frames.add(text.toString()); text.setLength(0); }
                socket.request(1);
                return null;
            }
        };
    }
}
