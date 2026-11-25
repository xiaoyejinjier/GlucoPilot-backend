package com.threadfit.glucopilot.food.gi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 新食品每分钟自动查找GI；独立线程避免外部网页/模型等待阻塞提现等定时任务。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "glucopilot.food.gi.enabled", havingValue = "true", matchIfMissing = true)
public class FoodGiLookupJob {
    private final FoodGiMapper mapper;
    private final SydneyGiLookupClient online;
    private final FoodGiWriter writer;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "food-gi-lookup");
        thread.setDaemon(true);
        return thread;
    });

    @Scheduled(initialDelayString = "${glucopilot.food.gi.initial-delay-ms:60000}",
            fixedDelayString = "${glucopilot.food.gi.delay-ms:60000}")
    public void scheduleLookup() {
        if (!running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { lookupMissing(); }
                catch (Exception error) {
                    log.warn("Food GI batch unavailable type={}", error.getClass().getSimpleName());
                } finally { running.set(false); }
            });
        } catch (RejectedExecutionException ignored) { running.set(false); }
    }

    public void lookupMissing() {
        mapper.enqueueMissing();
        for (var item : mapper.pending()) {
            String lease = UUID.randomUUID().toString();
            if (mapper.claim(item, lease) != 1) continue;
            FoodGiResult result;
            try {
                var reference = mapper.reference(item.getFoodName(), item.getCategoryCode());
                result = reference != null ? reference.result() : online.lookup(item.getFoodName());
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Food GI lookup unavailable type={}", error.getClass().getSimpleName());
                result = new FoodGiResult("RETRY", null, null, null, "外部GI检索暂不可用，稍后自动重试");
            }
            writer.complete(item, lease, result);
            if (Thread.currentThread().isInterrupted()) return;
        }
    }

    @PreDestroy
    public void shutdown() { worker.shutdownNow(); }
}
