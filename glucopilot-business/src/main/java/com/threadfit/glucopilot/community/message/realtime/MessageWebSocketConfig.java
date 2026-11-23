package com.threadfit.glucopilot.community.message.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
public class MessageWebSocketConfig implements WebSocketConfigurer {
    private final CommunityMessageSocket socket;
    private final MessageSocketHandshake handshake;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 必须先于MVC的 /messages/{peerUserId} 匹配，否则socket会被当作Long参数而返回400。
        ((ServletWebSocketHandlerRegistry) registry).setOrder(-1);
        // 允许同源/无Origin的原生客户端以及微信平台域；仍必须提供有效Bearer。
        registry.addHandler(socket, "/api/v1/community/messages/socket")
                .addInterceptors(handshake)
                .setAllowedOrigins("https://servicewechat.com");
    }

    @Bean
    public ServletServerContainerFactoryBean messageWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        container.setMaxBinaryMessageBufferSize(1024);
        container.setMaxSessionIdleTimeout(75000L);
        return container;
    }

}
