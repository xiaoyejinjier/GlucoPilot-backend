package com.threadfit.glucopilot.community.message.realtime;

import com.threadfit.glucopilot.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.nio.charset.StandardCharsets;

/** 提交后广播轻量失效事件；Redis帮助多实例互通，MySQL仍是唯一消息来源。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRealtimeRelay implements MessageListener {
    public static final String CHANNEL = "glucopilot:community:dm:changed:v1";
    private final StringRedisTemplate redis;
    private final JsonUtils json;
    private final CommunityMessageSocket socket;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(DirectMessageChanged event) {
        try { redis.convertAndSend(CHANNEL, json.toJson(event)); }
        catch (Exception error) {
            // 不能把已提交的消息变成发送失败；当前实例立即提示，其他实例靠重连/定时补拉恢复。
            log.warn("Private message relay unavailable type={}", error.getClass().getSimpleName());
            socket.changed(event);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var event = json.fromJson(new String(message.getBody(), StandardCharsets.UTF_8), DirectMessageChanged.class);
            if (event != null && event.senderId() > 0 && event.recipientId() > 0
                    && event.messageId() != null && event.messageId().matches("[1-9][0-9]{0,18}")
                    && ("MESSAGE".equals(event.kind()) || "READ".equals(event.kind()))) socket.changed(event);
        } catch (Exception error) {
            log.warn("Private message relay ignored type={}", error.getClass().getSimpleName());
        }
    }
}
