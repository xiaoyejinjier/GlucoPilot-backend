package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.config.AiModelCapability;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.model.dto.AiRuntimeStatusDtos;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.impl.AiRuntimeStatusServiceImpl;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时诊断接口：源判定与隐患收集。
 *
 * <p>这几个用例守的是「接口说 binding、实际却走 default」那类漂移：{@link AiRuntimeStatusServiceImpl}
 * 刻意不复用 {@code SceneModelResolver}（避免刷日志），代价是判定条件写了两份，
 * 一旦其中一份改了、另一份没跟上，这个功能就会开始说谎。用例优先覆盖"绑定不可用时必须
 * 如实标成 default 并给出 issue"。
 */
class AiRuntimeStatusServiceTest {

    private AiRuntimeConfigService runtimeConfig;
    private AiSceneBindingService sceneBindings;
    private AiRuntimeStatusService service;

    @BeforeEach
    void setUp() {
        runtimeConfig = mock(AiRuntimeConfigService.class);
        sceneBindings = mock(AiSceneBindingService.class);
        when(sceneBindings.binding(any())).thenReturn(AiSceneBindingService.SceneBinding.INHERIT);
        service = new AiRuntimeStatusServiceImpl(runtimeConfig, sceneBindings);
    }

    @Test
    @DisplayName("没有绑定时应如实报出后台默认模型，而不是 yaml")
    void 没有绑定时应报出后台默认模型() {
        when(runtimeConfig.snapshot()).thenReturn(snapshot(textSpec("deepseek-v4-pro", "db"),
                visionSpec("qwen3-vl-plus", "db"), 0));

        AiRuntimeStatusDtos.Status status = service.status();

        AiRuntimeStatusDtos.SceneStatus chat = scene(status, SceneCode.CHAT);
        assertThat(chat.getSource()).isEqualTo("default");
        assertThat(chat.getModelCode()).isEqualTo("deepseek-v4-pro");
        assertThat(chat.getBoundModelCode()).isNull();
        assertThat(status.getIssues()).isEmpty();
        assertThat(status.getScenes()).hasSize(SceneCode.values().length);
    }

    @Test
    @DisplayName("绑定可用时来源是 binding，并带出绑定的编码")
    void 绑定可用时来源是binding() {
        when(sceneBindings.binding(SceneCode.CHAT))
                .thenReturn(binding("deepseek-v4-flash"));
        when(runtimeConfig.specByModelCode("deepseek-v4-flash"))
                .thenReturn(textSpec("deepseek-v4-flash", "db"));
        when(runtimeConfig.snapshot()).thenReturn(snapshot(textSpec("deepseek-v4-pro", "db"),
                visionSpec("qwen3-vl-plus", "db"), 0));

        AiRuntimeStatusDtos.SceneStatus chat = scene(service.status(), SceneCode.CHAT);

        assertThat(chat.getSource()).isEqualTo("binding");
        assertThat(chat.getModelCode()).isEqualTo("deepseek-v4-flash");
        assertThat(chat.getBoundModelCode()).isEqualTo("deepseek-v4-flash");
        assertThat(chat.getBindingUsable()).isTrue();
    }

    @Test
    @DisplayName("视觉场景绑了文本模型时绑定不可用，回落默认并记一条 issue")
    void 视觉场景绑文本模型时绑定不可用() {
        when(sceneBindings.binding(SceneCode.CHAT_IMAGE))
                .thenReturn(binding("deepseek-v4-pro"));
        // 编码存在、行也是启用的，但能力不含 vision —— 这才是"能力不匹配"该走的分支
        when(runtimeConfig.specByModelCode("deepseek-v4-pro"))
                .thenReturn(textSpec("deepseek-v4-pro", "db"));
        when(runtimeConfig.snapshot()).thenReturn(snapshot(textSpec("deepseek-v4-pro", "db"),
                visionSpec("qwen3-vl-plus", "db"), 0));

        AiRuntimeStatusDtos.Status status = service.status();
        AiRuntimeStatusDtos.SceneStatus image = scene(status, SceneCode.CHAT_IMAGE);

        assertThat(image.getSource()).isEqualTo("default");
        assertThat(image.getModelCode()).isEqualTo("qwen3-vl-plus");
        assertThat(image.getBindingUsable()).isFalse();
        assertThat(status.getIssues()).anyMatch(issue -> issue.contains("CHAT_IMAGE")
                && issue.contains("能力不匹配"));
    }

    @Test
    @DisplayName("绑定指向不存在的模型时也要报出来，而不是静默回落")
    void 绑定指向不存在的模型时应报出() {
        when(sceneBindings.binding(SceneCode.SUMMARY)).thenReturn(binding("ghost-model"));
        when(runtimeConfig.snapshot()).thenReturn(snapshot(textSpec("deepseek-v4-pro", "db"),
                visionSpec("qwen3-vl-plus", "db"), 0));

        AiRuntimeStatusDtos.Status status = service.status();

        assertThat(scene(status, SceneCode.SUMMARY).getSource()).isEqualTo("default");
        assertThat(status.getIssues()).anyMatch(issue -> issue.contains("ghost-model"));
    }

    @Test
    @DisplayName("DB 里没有默认模型时来源是 fallback 并提示去后台补默认")
    void 没有后台默认时来源是fallback() {
        when(runtimeConfig.snapshot()).thenReturn(snapshot(textSpec("deepseek-v4-flash", "yaml"),
                visionSpec("qwen3-vl-plus", "yaml"), 0));

        AiRuntimeStatusDtos.Status status = service.status();

        assertThat(scene(status, SceneCode.CHAT).getSource()).isEqualTo("fallback");
        assertThat(status.getTextDefault().getSource()).isEqualTo("yaml");
        assertThat(status.getIssues()).anyMatch(issue -> issue.contains("文本通道没有后台默认模型"));
        assertThat(status.getIssues()).anyMatch(issue -> issue.contains("视觉通道没有后台模型"));
    }

    @Test
    @DisplayName("有被跳过的模型行时应提示去看启动日志")
    void 有被跳过的行时应提示() {
        when(runtimeConfig.snapshot()).thenReturn(snapshot(textSpec("deepseek-v4-pro", "db"),
                visionSpec("qwen3-vl-plus", "db"), 2));

        AiRuntimeStatusDtos.Status status = service.status();

        assertThat(status.getSkipped()).isEqualTo(2);
        assertThat(status.getIssues()).anyMatch(issue -> issue.contains("2 行模型配置被跳过"));
    }

    @Test
    @DisplayName("视觉默认没有显式默认标记时应建议去设置，否则新增视觉模型会悄悄换默认")
    void 视觉默认没有显式标记时应提示() {
        AiModelSpec text = textSpec("deepseek-v4-pro", "db");
        AiModelSpec vision = visionSpec("qwen3-vl-plus", "db");
        when(runtimeConfig.snapshot()).thenReturn(new AiRuntimeConfigService.Snapshot(1L,
                Map.of(text.modelCode(), text, vision.modelCode(), vision),
                Map.of(text.modelEndpoint(), text, vision.modelEndpoint(), vision),
                textSpec("deepseek-v4-flash", "yaml"), visionSpec("qwen3-vl-plus", "yaml"),
                text, "默认标记", vision, "唯一启用的视觉模型", 0));

        AiRuntimeStatusDtos.Status status = service.status();

        assertThat(status.getIssues()).anyMatch(issue -> issue.contains("没有显式默认模型"));
    }

    // ------------------------------------------------------------------ 构造

    private static AiRuntimeStatusDtos.SceneStatus scene(AiRuntimeStatusDtos.Status status, SceneCode code) {
        return status.getScenes().stream()
                .filter(item -> code.getCode().equals(item.getSceneCode()))
                .findFirst()
                .orElseThrow();
    }

    private static AiSceneBindingService.SceneBinding binding(String modelCode) {
        return new AiSceneBindingService.SceneBinding(SceneCode.CHAT.getCode(), modelCode,
                null, null, null, "db");
    }

    private static AiRuntimeConfigService.Snapshot snapshot(AiModelSpec textDefault, AiModelSpec visionDefault,
                                                            int skipped) {
        Map<String, AiModelSpec> byCode = new HashMap<>();
        Map<String, AiModelSpec> byName = new HashMap<>();
        if ("db".equals(textDefault.source())) {
            byCode.put(textDefault.modelCode(), textDefault);
            byName.put(textDefault.modelEndpoint(), textDefault);
        }
        if ("db".equals(visionDefault.source())) {
            byCode.put(visionDefault.modelCode(), visionDefault);
            byName.put(visionDefault.modelEndpoint(), visionDefault);
        }
        return new AiRuntimeConfigService.Snapshot(1L, byCode, byName,
                textSpec("deepseek-v4-flash", "yaml"), visionSpec("qwen3-vl-plus", "yaml"),
                textDefault, "默认标记", visionDefault, "默认标记", skipped);
    }

    private static AiModelSpec textSpec(String code, String source) {
        return new AiModelSpec(code, code, 1, "https://api.deepseek.com", "sk-test",
                code, 0.7, null, 2048, null, Duration.ofSeconds(60), source, AiModelCapability.text());
    }

    private static AiModelSpec visionSpec(String code, String source) {
        return new AiModelSpec(code, code, 2, "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-vl",
                code, 0.2, null, 512, null, Duration.ofSeconds(60), source,
                AiModelCapability.visionDefaults());
    }
}
