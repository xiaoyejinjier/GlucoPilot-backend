package com.threadfit.glucopilot.community.message.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class MessageRealtimeRedisConfig {
    @Bean
    public RedisMessageListenerContainer messageRealtimeListener(RedisConnectionFactory factory, MessageRealtimeRelay relay) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(relay, new ChannelTopic(MessageRealtimeRelay.CHANNEL));
        return container;
    }
}
