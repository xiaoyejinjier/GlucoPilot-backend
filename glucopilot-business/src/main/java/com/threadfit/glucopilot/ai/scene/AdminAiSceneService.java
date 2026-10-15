package com.threadfit.glucopilot.ai.scene;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.model.AiModelConfigEntity;
import com.threadfit.glucopilot.ai.model.AiModelConfigMapper;
import com.threadfit.glucopilot.ai.model.AiModelSpec;
import com.threadfit.glucopilot.ai.model.AiRuntimeConfigService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 后台"场景配置"（场景 → 模型 / 场景参数）的业务逻辑。
 *
 * <h2>与提示词管理的关系</h2>
 * 提示词表 {@code ai_prompt_template} 里本来就有一列 {@code model_code}（"这个场景用哪个模型"），
 * 但它与"提示词版本"耦合在一起：换模型要新增一个提示词版本才能带上，语义很绕。
 * 因此场景绑定单列一张 {@code ai_scene_config}（V17），提示词表那一列保留兼容、不再由本接口写入。
 *
 * <h2>优先级与生效范围（与运行时完全一致，改完所见即所得）</h2>
 * DB 非空字段 &gt; yaml {@code ai.scenes.<scene>.*} &gt; 通道默认；其中：
 * <ul>
 *   <li>{@code model_code}：<b>仅文本场景生效</b>。视觉场景的模型由视觉通道统一解析
 *       （{@code ai_model_config} 里 provider=2 的那一行），本接口会照存并提示不生效（P2 再做）；</li>
 *   <li>{@code temperature / max_tokens}：文本走请求级 DeepSeek options，视觉走请求级 OpenAI options，都生效；</li>
 *   <li>{@code thinking}：DeepSeek 专有，视觉场景忽略。</li>
 * </ul>
 * 保存成功后调用 {@link AiSceneBindingService#refreshNow()}，本实例立即生效（其他实例最多滞后 60 秒）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAiSceneService {

    private static final Map<Integer, String> PROVIDER_TEXT = Map.of(
            1, "DeepSeek（自研 SDK）",
            2, "OpenAI 兼容",
            3, "通义千问",
            4, "文心一言",
            5, "其他");

    private final AiSceneConfigMapper mapper;

    private final AiSceneBindingService sceneBindings;

    private final AiModelConfigMapper modelMapper;

    private final AiRuntimeConfigService runtimeConfig;

    private final AIProperties aiProperties;

    // ------------------------------------------------------------------ 查询

    public AdminAiSceneDtos.SceneList list() {
        List<AdminAiSceneDtos.SceneRow> rows = new ArrayList<>();
        for (SceneCode scene : SceneCode.values()) {
            rows.add(toRow(scene, find(scene)));
        }
        return AdminAiSceneDtos.SceneList.builder()
                .records(rows)
                .models(modelOptions())
                .build();
    }

    private List<AdminAiSceneDtos.ModelOption> modelOptions() {
        List<AdminAiSceneDtos.ModelOption> options = new ArrayList<>();
        for (AiModelConfigEntity entity : modelMapper.selectList(
                new LambdaQueryWrapper<AiModelConfigEntity>()
                        .eq(AiModelConfigEntity::getIsEnabled, 1)
                        .orderByDesc(AiModelConfigEntity::getIsDefault)
                        .orderByAsc(AiModelConfigEntity::getModelCode))) {
            options.add(AdminAiSceneDtos.ModelOption.builder()
                    .code(entity.getModelCode())
                    .name(StringUtils.hasText(entity.getModelName())
                            ? entity.getModelName() : entity.getModelCode())
                    .providerCode(entity.getProviderCode())
                    .isDefault(entity.getIsDefault() != null && entity.getIsDefault() == 1)
                    .providerName(PROVIDER_TEXT.getOrDefault(entity.getProviderCode(), "未知"))
                    .build());
        }
        return options;
    }

    // ------------------------------------------------------------------ 保存

    @Transactional
    public AdminAiSceneDtos.SceneRow save(String sceneCode, AdminAiSceneDtos.SaveRequest request) {
        SceneCode scene = SceneCode.findByCodeOrConfigKey(sceneCode);
        if (scene == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知的场景编码：" + sceneCode);
        }
        boolean clear = Boolean.TRUE.equals(request.getClear());
        if (!clear && request.getModelCode() != null && StringUtils.hasText(request.getModelCode())) {
            // 绑定的编码必须存在且启用：否则运行时会 WARN 并回落，运营却以为已经绑上了
            AiModelSpec spec = runtimeConfig.specByModelCode(request.getModelCode().trim());
            if (spec == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "模型编码不存在或已停用：" + request.getModelCode() + "（请先在「模型配置」里启用它）");
            }
            if (scene.isRequiresVision()) {
                log.warn("后台为视觉场景 {} 绑定了 model_code={}：该字段对视觉场景不生效"
                                + "（视觉模型由通道统一解析），本次仍照常保存供 P2 使用",
                        scene.getCode(), request.getModelCode());
            }
        }

        AiSceneConfigEntity existing = find(scene);
        AiSceneConfigEntity entity = new AiSceneConfigEntity();
        entity.setSceneCode(scene.getCode());
        if (clear) {
            entity.setModelCode(null);
            entity.setTemperature(null);
            entity.setMaxTokens(null);
            entity.setThinking(null);
        } else {
            entity.setModelCode(blankToNull(request.getModelCode()));
            entity.setTemperature(request.getTemperature());
            entity.setMaxTokens(request.getMaxTokens());
            entity.setThinking(request.getThinking());
        }
        entity.setIsEnabled(request.getStatus() == null ? 1 : request.getStatus());
        entity.setRemark(request.getRemark());

        if (existing == null) {
            entity.setConfigVersion(1L);
            mapper.insert(entity);
            log.info("后台新增场景配置 scene={} model={} temperature={} maxTokens={} thinking={}",
                    scene.getCode(), entity.getModelCode(), entity.getTemperature(),
                    entity.getMaxTokens(), entity.getThinking());
        } else {
            if (request.getConfigVersion() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少 configVersion（乐观锁），请刷新后重试");
            }
            entity.setId(existing.getId());
            if (mapper.updateWithVersion(entity, request.getConfigVersion()) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "该场景配置已被他人修改，请刷新后重试");
            }
            log.info("后台修改场景配置 scene={} id={} model={} temperature={} maxTokens={} thinking={} 清空={}",
                    scene.getCode(), existing.getId(), entity.getModelCode(), entity.getTemperature(),
                    entity.getMaxTokens(), entity.getThinking(), clear);
        }

        sceneBindings.refreshNow();
        return toRow(scene, find(scene));
    }

    // ------------------------------------------------------------------ 内部

    private AiSceneConfigEntity find(SceneCode scene) {
        return mapper.selectOne(new LambdaQueryWrapper<AiSceneConfigEntity>()
                .eq(AiSceneConfigEntity::getSceneCode, scene.getCode()));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private AdminAiSceneDtos.SceneRow toRow(SceneCode scene, AiSceneConfigEntity entity) {
        AIProperties.SceneProperties yaml = aiProperties.scene(scene);
        String effectiveModelCode;
        String effectiveModelSource;
        if (scene.isRequiresVision()) {
            AiModelSpec spec = runtimeConfig.visionSpec();
            effectiveModelCode = spec.modelCode();
            effectiveModelSource = spec.source();
        } else {
            AiModelSpec spec = runtimeConfig.textSpec(scene);
            effectiveModelCode = spec.modelCode();
            effectiveModelSource = spec.source();
        }
        return AdminAiSceneDtos.SceneRow.builder()
                .sceneCode(scene.getCode())
                .configKey(scene.getConfigKey())
                .sceneName(scene.getDisplayName())
                .requiresVision(scene.isRequiresVision())
                .modelCode(entity == null ? null : entity.getModelCode())
                .temperature(entity == null ? null : entity.getTemperature())
                .maxTokens(entity == null ? null : entity.getMaxTokens())
                .thinking(entity == null ? null : entity.getThinking())
                .status(entity == null ? null : entity.getIsEnabled())
                .configVersion(entity == null ? null : entity.getConfigVersion())
                .remark(entity == null ? null : entity.getRemark())
                .configured(entity != null)
                .effectiveModelCode(effectiveModelCode)
                .effectiveModelSource(effectiveModelSource)
                .yamlModelCode(yaml.getModelCode())
                .yamlTemperature(toBigDecimal(yaml.getTemperature()))
                .yamlMaxTokens(yaml.getMaxTokens())
                .yamlThinking(yaml.getThinking())
                .modelCodeApplies(!scene.isRequiresVision())
                .hint(hint(scene))
                .build();
    }

    private static String hint(SceneCode scene) {
        if (scene.isRequiresVision()) {
            return "视觉场景：模型由视觉通道统一解析（模型配置里 provider=2 的那一行），"
                    + "本页的温度/输出上限会作为请求参数生效；model_code 暂不生效。";
        }
        if (scene == SceneCode.CHAT) {
            return "文本场景：模型编码、温度、输出上限、思维链都会生效；留空则继承 yaml / 通道默认。";
        }
        return "留空则继承 yaml / 通道默认。";
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
