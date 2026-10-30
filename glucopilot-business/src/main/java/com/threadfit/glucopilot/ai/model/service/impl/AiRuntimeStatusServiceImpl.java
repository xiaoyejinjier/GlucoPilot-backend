package com.threadfit.glucopilot.ai.model.service.impl;

import com.threadfit.glucopilot.ai.config.AiModelCapability;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.model.dto.AiRuntimeStatusDtos;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeStatusService;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「快照 + 场景绑定」翻译成一份人可读的现状，并收集可疑配置。
 *
 * <p>这里刻意<b>不</b>复用 {@code SceneModelResolver.modelCode}：那个方法每次不可用都会 WARN，
 * 而诊断接口会被反复调用（后台刷新、排障脚本），不能把日志刷爆。这边只做同样的判定、不打日志，
 * 判定规则一旦和 resolver 漂移，就会变成"接口说 binding、实际走 default"——所以两边的
 * 条件必须逐字一致：{@code bound != null && bound.vision()} / {@code contains(TEXT)}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRuntimeStatusServiceImpl implements AiRuntimeStatusService {

    private final AiRuntimeConfigService runtimeConfig;
    private final AiSceneBindingService sceneBindings;

    @Override
    public AiRuntimeStatusDtos.Status status() {
        AiRuntimeConfigService.Snapshot snapshot = runtimeConfig.snapshot();
        List<String> issues = new ArrayList<>();
        List<AiRuntimeStatusDtos.SceneStatus> scenes = new ArrayList<>();

        AiModelSpec textDefault = snapshot.resolveTextDefault();
        AiModelSpec visionDefault = snapshot.resolveVisionDefault();

        for (SceneCode scene : SceneCode.values()) {
            scenes.add(sceneStatus(scene, snapshot, issues));
        }

        if ("yaml".equals(textDefault.source())) {
            issues.add("文本通道没有后台默认模型，正在使用 yaml 兜底 " + textDefault.modelCode()
                    + "；请到「模型配置」把某个文本模型设为默认");
        }
        if ("yaml".equals(visionDefault.source())) {
            issues.add("视觉通道没有后台模型，正在使用 yaml 兜底 " + visionDefault.modelEndpoint()
                    + "；请到「模型配置」新增一个能力含 vision 的模型");
        }
        if (AiRuntimeConfigServiceImpl.VISION_ONLY_ENABLED.equals(snapshot.visionDefaultReason())
                || AiRuntimeConfigServiceImpl.VISION_RECENT.equals(snapshot.visionDefaultReason())) {
            // 没有显式默认标记时，视觉默认是"当前唯一启用的那个"：再启用第二个视觉模型就会悄悄换掉
            issues.add("视觉通道没有显式默认模型，当前用的是「" + snapshot.visionDefaultReason() + "」"
                    + visionDefault.modelCode() + "；建议在「模型配置」把它设为默认，避免新增视觉模型后默认被换掉");
        }
        if (snapshot.skipped() > 0) {
            issues.add("有 " + snapshot.skipped() + " 行模型配置被跳过（密钥解不开或字段非法），详见启动日志");
        }
        return AiRuntimeStatusDtos.Status.builder()
                .version(snapshot.version())
                .dbModelCount(snapshot.dbModelCount())
                .skipped(snapshot.skipped())
                .textDefault(brief(textDefault, snapshot.textDefaultReason()))
                .visionDefault(brief(visionDefault, snapshot.visionDefaultReason()))
                .scenes(scenes)
                .issues(issues)
                .sampledAt(LocalDateTime.now())
                .build();
    }

    private AiRuntimeStatusDtos.SceneStatus sceneStatus(SceneCode scene,
                                                        AiRuntimeConfigService.Snapshot snapshot,
                                                        List<String> issues) {
        AiSceneBindingService.SceneBinding binding = sceneBindings.binding(scene);
        String boundModelCode = binding.hasModel() ? binding.modelCode() : null;
        boolean bound = false;
        AiModelSpec boundSpec = null;
        if (boundModelCode != null) {
            boundSpec = runtimeConfig.specByModelCode(boundModelCode);
            bound = boundSpec != null
                    && (scene.isRequiresVision() ? boundSpec.vision() : boundSpec.capability().contains(AiModelCapability.TEXT));
            if (!bound) {
                issues.add("场景 " + scene.getCode() + " 绑定的模型 " + boundModelCode
                        + " 不可用（不存在、已停用，或能力不匹配），实际回落到通道默认");
            }
        }

        AiModelSpec effective;
        String source;
        if (bound) {
            effective = boundSpec;
            source = "binding";
        } else {
            effective = scene.isRequiresVision() ? snapshot.resolveVisionDefault() : snapshot.resolveTextDefault();
            source = "yaml".equals(effective.source()) ? "fallback" : "default";
        }
        return AiRuntimeStatusDtos.SceneStatus.builder()
                .sceneCode(scene.getCode())
                .sceneName(scene.getDisplayName())
                .requiresVision(scene.isRequiresVision())
                .modelCode(effective.modelCode())
                .modelEndpoint(effective.modelEndpoint())
                .source(source)
                .boundModelCode(boundModelCode)
                .bindingUsable(boundModelCode == null ? null : bound)
                .build();
    }

    private static AiRuntimeStatusDtos.ModelBrief brief(AiModelSpec spec, String reason) {
        return AiRuntimeStatusDtos.ModelBrief.builder()
                .modelCode(spec.modelCode())
                .modelEndpoint(spec.modelEndpoint())
                .source(spec.source())
                .reason(reason)
                .capability(spec.capability().value())
                .build();
    }
}
