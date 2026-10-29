package com.threadfit.glucopilot.user.cancellation;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.IdUtils;
import com.threadfit.glucopilot.user.dto.CancellationDtos.CaptchaView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动验证票据与发码频控。
 *
 * <p>滑块本身只是 UX 门槛：前端拖到最右侧后向服务端取一次性票据，发码时必须回传。
 * 服务端不比对滑动轨迹，防刷完全依赖这里的三层频控 + {@link AccountCancellationServiceImpl}
 * 的验证失败锁定：
 * <ul>
 *   <li>票据绑定 userId，180 秒过期，一次性消费；</li>
 *   <li>取票频控：用户 20 次/小时、IP 60 次/小时；</li>
 *   <li>发码频控：用户 10 次/天、IP 50 次/天（手机号/邮箱 60 秒冷却仍由 AuthService 负责）。</li>
 * </ul>
 * 进程内内存实现，不引入 Redis；多实例部署时按实例叠加，后续再统一下沉到网关/WAF。
 */
@Service
@EnableScheduling
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private static final long TICKET_TTL_MS = 180_000L;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;

    private static final int TICKET_USER_PER_HOUR = 20;
    private static final int TICKET_IP_PER_HOUR = 60;
    private static final int SEND_USER_PER_DAY = 10;
    private static final int SEND_IP_PER_DAY = 50;

    /** ticket -> 持有人与过期时间。 */
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    /** 频控窗口：key -> 当前窗口计数。 */
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** 取票：绑定当前 userId，返回一次性票据。 */
    public CaptchaView issue(Long userId, String clientIp) {
        long now = System.currentTimeMillis();
        if (!acquire("ticket:user:" + userId, TICKET_USER_PER_HOUR, HOUR_MS, now)
                || !acquire("ticket:ip:" + clientIp, TICKET_IP_PER_HOUR, HOUR_MS, now)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        String ticket = IdUtils.uuid();
        tickets.put(ticket, new Ticket(userId, now + TICKET_TTL_MS));
        log.info("captcha.issue userId={} ip={} expiresIn={}s", userId, clientIp, TICKET_TTL_MS / 1000);
        return CaptchaView.builder()
                .ticket(ticket)
                .expiresIn((int) (TICKET_TTL_MS / 1000))
                .build();
    }

    /**
     * 消费票据：不存在、已过期、非本人持有或已被并发消费都视为失效。
     * 校验通过才移除，避免他人用错误 userId 提前消耗掉票据。
     */
    public void consume(String ticket, Long userId) {
        if (!StringUtils.hasText(ticket)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先完成滑动验证");
        }
        Ticket current = tickets.get(ticket);
        if (current == null
                || current.expireAt() < System.currentTimeMillis()
                || !current.userId().equals(userId)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "滑动验证已失效，请重新验证");
        }
        if (!tickets.remove(ticket, current)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "滑动验证已失效，请重新验证");
        }
    }

    /** 发码日限额：用户 10 次、IP 50 次。校验失败锁定仍由注销服务自行维护。 */
    public void guardSendCode(Long userId, String clientIp) {
        long now = System.currentTimeMillis();
        if (!acquire("send:user:" + userId, SEND_USER_PER_DAY, DAY_MS, now)
                || !acquire("send:ip:" + clientIp, SEND_IP_PER_DAY, DAY_MS, now)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "今日发送次数已达上限，请明天再试");
        }
    }

    /** 定时清理：过期票据与滑出窗口的频控计数，防止内存无界增长。 */
    @Scheduled(fixedDelayString = "${account.cancellation.captcha-clean-delay:60000}")
    public void cleanup() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue().expireAt() < now);
        windows.entrySet().removeIf(entry -> now - entry.getValue().start >= DAY_MS);
    }

    private boolean acquire(String key, int limit, long windowMs, long now) {
        int[] used = new int[1];
        windows.compute(key, (k, current) -> {
            Window next = (current == null || now - current.start >= windowMs)
                    ? new Window(now)
                    : current;
            next.count++;
            used[0] = next.count;
            return next;
        });
        return used[0] <= limit;
    }

    private record Ticket(Long userId, long expireAt) {
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
