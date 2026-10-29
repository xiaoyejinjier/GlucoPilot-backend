package com.threadfit.glucopilot.ai.scene.service.impl;

import com.threadfit.glucopilot.ai.core.SceneCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.threadfit.glucopilot.ai.scene.entity.AiSceneConfigEntity;
import com.threadfit.glucopilot.ai.scene.mapper.AiSceneConfigMapper;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;

/**
 * 场景绑定的运行时视图：把 {@code ai_scene_config} 合成一份不可变快照。
 *
 * <h2>为什么与 AiRuntimeConfigService 分开</h2>
 * 模型通道（用哪套凭据）与场景绑定（这个场景挑哪个模型、什么参数）是两个正交的轴：
 * 前者由 {@code ai_model_config} 决定，后者由本表决定。分开之后各自刷新、各自降级，
 * 一边配错不会把另一边带下水。
 *
 * <h2>与 yaml 的关系</h2>
 * 优先级：<b>DB 非空字段 &gt; yaml {@code ai.scenes.<scene>.*} &gt; 通道默认</b>。
 * 因此"DB 里没配"完全等价于迁移前的行为——这是本次上线零风险的关键。
 *
 * <h2>刷新</h2>
 * 内容指纹 + 60 秒 TTL：本实例后台改完立即生效（{@link #refreshNow()}），其他实例最多滞后 60 秒。
 */
@Slf4j
@Component
public class AiSceneBindingServiceImpl implements AiSceneBindingService {

    private static final Duration REFRESH_TTL = Duration.ofSeconds(60);

    private final AiSceneConfigMapper mapper;

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final Object loadLock = new Object();
    private volatile long loadedAtNanos;
    private volatile String loadedFingerprint;
    private long versionCounter;

    public AiSceneBindingServiceImpl(AiSceneConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 某场景的绑定；没有配置时返回 {@link SceneBinding#INHERIT}（永不返回 null，调用方不必判空）。 */
    @Override
    public SceneBinding binding(SceneCode scene) {
        Snapshot snapshot = snapshot();
        SceneBinding binding = snapshot.bySceneCode().get(scene.getCode());
        return binding == null ? SceneBinding.INHERIT : binding;
    }

    @Override
    public Snapshot snapshot() {
        Snapshot existing = current.get();
        if (existing == null || isStale()) {
            synchronized (loadLock) {
                if (current.get() == null || isStale()) {
                    load();
                }
            }
        }
        return current.get();
    }

    /** 后台保存后调用：本实例立即生效。 */
    @Override
    public void refreshNow() {
        synchronized (loadLock) {
            load();
        }
    }

    private boolean isStale() {
        return System.nanoTime() - loadedAtNanos > REFRESH_TTL.toNanos();
    }

    private void load() {
        Snapshot previous = current.get();
        try {
            Map<String, SceneBinding> bySceneCode = new LinkedHashMap<>();
            for (AiSceneConfigEntity row : mapper.selectEnabled()) {
                SceneCode scene = SceneCode.findByCode(row.getSceneCode());
                if (scene == null) {
                    log.warn("库表场景编码无法识别，已忽略：scene_code={} id={}", row.getSceneCode(), row.getId());
                    continue;
                }
                if (scene.isRequiresVision() && StringUtils.hasText(row.getModelCode())) {
                    log.warn("场景 {} 需要视觉模型，但其 model_code={} 不会被使用：视觉模型由通道统一解析"
                                    + "（ai_model_config 里 provider=2 的行 / ai.vision.model）。"
                                    + "该配置已保存，逐场景选视觉模型属 P2。",
                            scene.getCode(), row.getModelCode());
                }
                // 以枚举里的编码为键：库里写成小写/带空格时也能命中，而不是静默"配置了却不生效"
                bySceneCode.put(scene.getCode(), toBinding(row));
            }
            String fingerprint = fingerprintOf(bySceneCode);
            if (!fingerprint.equals(loadedFingerprint)) {
                loadedFingerprint = fingerprint;
                versionCounter++;
            }
            long version = versionCounter;
            current.set(new Snapshot(version, bySceneCode));
            loadedAtNanos = System.nanoTime();
            if (previous == null || previous.version() != version) {
                log.info("AI 场景绑定已加载：生效 {} 个场景{}", bySceneCode.size(),
                        bySceneCode.isEmpty() ? "（全部继承 yaml/通道默认）" : "：" + bySceneCode.keySet());
            }
        } catch (RuntimeException e) {
            // 与模型配置同一条原则：读库失败不能把 AI 打挂，沿用上一份快照即可
            if (previous == null) {
                log.error("AI 场景绑定首次加载失败，全部按 yaml/通道默认运行", e);
                current.set(new Snapshot(System.nanoTime(), Map.of()));
            } else {
                log.error("AI 场景绑定加载失败，继续沿用上一份快照（版本 {}）", previous.version(), e);
            }
            loadedAtNanos = System.nanoTime();
        }
    }

    private static SceneBinding toBinding(AiSceneConfigEntity row) {
        return new SceneBinding(
                row.getSceneCode(),
                trimToNull(row.getModelCode()),
                toDouble(row.getTemperature()),
                row.getMaxTokens(),
                row.getThinking(),
                "db");
    }

    private static String fingerprintOf(Map<String, SceneBinding> bySceneCode) {
        StringBuilder sb = new StringBuilder();
        bySceneCode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(entry.getKey()).append('=')
                        .append(entry.getValue().buildFingerprint()).append(';'));
        return sb.toString();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

}