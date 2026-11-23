package com.threadfit.glucopilot.community.message.realtime;

import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageRealtimeRelayTest {
    AnnotationConfigApplicationContext context;
    StringRedisTemplate redis;
    CommunityMessageSocket socket;
    TransactionTemplate tx;
    final DirectMessageChanged event = new DirectMessageChanged(1, 2, "18", "MESSAGE");

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Setup {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean CommunityMessageSocket socket() { return mock(CommunityMessageSocket.class); }
        @Bean JsonUtils json() { return new JsonUtils(new ObjectMapper()); }
        @Bean MessageRealtimeRelay relay(StringRedisTemplate redis, JsonUtils json, CommunityMessageSocket socket) {
            return new MessageRealtimeRelay(redis, json, socket);
        }
        @Bean DataSourceTransactionManager transactions() {
            return new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:socket_tx", "sa", ""));
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Setup.class);
        redis = context.getBean(StringRedisTemplate.class);
        socket = context.getBean(CommunityMessageSocket.class);
        tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        clearInvocations(redis, socket); // Spring初始化回调不是业务推送。
    }
    @AfterEach void cleanup() { context.close(); }

    @Test void notificationOnlyRunsAfterCommitAndRollbackNeverPublishes() {
        tx.executeWithoutResult(status -> {
            context.publishEvent(event);
            verifyNoInteractions(redis, socket);
        });
        verify(redis).convertAndSend(eq(MessageRealtimeRelay.CHANNEL), contains("\"messageId\":\"18\""));
        reset(redis, socket);
        tx.executeWithoutResult(status -> { context.publishEvent(event); status.setRollbackOnly(); });
        verifyNoInteractions(redis, socket);
        context.publishEvent(event); // 无事务不能假装已经提交。
        verifyNoInteractions(redis, socket);
    }

    @Test void redisFailureCannotTurnCommittedSendIntoFailureAndLocalDeliveryStillWorks() {
        when(redis.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> context.publishEvent(event)));
        verify(socket).changed(event);
    }

    @Test void subscriberValidatesEventAndDeliversOnlyIdentifiers() {
        var relay = context.getBean(MessageRealtimeRelay.class);
        var json = context.getBean(JsonUtils.class);
        relay.onMessage(redisMessage(json.toJson(event)), null);
        verify(socket).changed(event);
        reset(socket);
        for (String body : new String[] {"{}", "null", "bad-json", "{\"senderId\":-1,\"recipientId\":2,\"messageId\":\"18\",\"kind\":\"MESSAGE\"}"})
            relay.onMessage(redisMessage(body), null);
        verifyNoInteractions(socket);
    }

    private Message redisMessage(String body) {
        var message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
