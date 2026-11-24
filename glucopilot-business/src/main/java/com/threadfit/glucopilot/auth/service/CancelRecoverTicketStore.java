package com.threadfit.glucopilot.auth.service;

import com.threadfit.glucopilot.common.util.IdUtils;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 注销冷静期恢复凭据：凭证校验通过后签发，只能消费一次，不携带任何业务权限。
 *
 * <p>刻意不使用「临时 Token」：临时 Token 需要进入 user_session 或被鉴权过滤器识别，
 * 容易出现误放行其他接口；这里用 Redis 票据把权限边界收敛到「换一次恢复」本身。
 */
@Component
@RequiredArgsConstructor
public class CancelRecoverTicketStore {

    /** 票据有效期：足够用户读完弹窗并做决定，又不给重放留太大窗口。 */
    public static final long TTL_SECONDS = 300;

    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);
    private static final String PREFIX = "auth:cancel-recover:ticket:";

    private final StringRedisTemplate redis;

    /** 签发一次性票据，绑定用户与登录方式。 */
    public String issue(Long userId, int loginType) {
        String ticket = IdUtils.uuid();
        redis.opsForValue().set(PREFIX + ticket, userId + ":" + loginType, TTL);
        return ticket;
    }

    /**
     * 原子消费：过期、已使用、伪造均返回 null。
     *
     * <p>GETDEL 保证「同一张票最多换一次恢复」，双击 / 多开页面不会重复恢复。
     */
    public Ticket consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        String value = redis.opsForValue().getAndDelete(PREFIX + ticket);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":");
        return new Ticket(Long.valueOf(parts[0]), Integer.parseInt(parts[1]));
    }

    public record Ticket(Long userId, int loginType) {
    }
}
