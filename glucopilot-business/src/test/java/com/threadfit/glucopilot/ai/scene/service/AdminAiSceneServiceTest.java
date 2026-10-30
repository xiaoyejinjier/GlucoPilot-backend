package com.threadfit.glucopilot.ai.scene.service;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiModelCapability;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.model.entity.AiModelConfigEntity;
import com.threadfit.glucopilot.ai.model.mapper.AiModelConfigMapper;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.scene.dto.AdminAiSceneDtos;
import com.threadfit.glucopilot.ai.scene.entity.AiSceneConfigEntity;
import com.threadfit.glucopilot.ai.scene.mapper.AiSceneConfigMapper;
import com.threadfit.glucopilot.ai.scene.service.AdminAiSceneService;
import com.threadfit.glucopilot.ai.scene.service.AiSceneBindingService;
import com.threadfit.glucopilot.ai.scene.service.impl.AdminAiSceneServiceImpl;

/**
 * 后台"场景配置"的语义。
 * <p>
 * 这里守的是运营最容易踩的四个坑：
 * <ol>
 *   <li>绑了一个不存在/已停用的模型编码 → 运行时只会 WARN 回落，运营却以为绑上了 → 保存时就 400 拦掉；</li>
 *   <li>以为是"没传就不改"，实际是"留空=继承"→ 接口文档与页面都必须说清（PUT 整体覆盖语义）；</li>
 *   <li>两个管理员同时改 → 必须靠 configVersion 报 409，而不是后写覆盖先写；</li>
 *   <li>视觉场景的 model_code 不生效 → 允许保存但必须在返回值里说清（不静默）。</li>
 * </ol>
 */
class AdminAiSceneServiceTest {

    private AiSceneConfigMapper mapper;

    private AiSceneBindingService sceneBindings;

    private AiModelConfigMapper modelMapper;

    private AiRuntimeConfigService runtimeConfig;

    private AIProperties properties;

    private AdminAiSceneService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiSceneConfigMapper.class);
        sceneBindings = mock(AiSceneBindingService.class);
        modelMapper = mock(AiModelConfigMapper.class);
        runtimeConfig = mock(AiRuntimeConfigService.class);
        properties = new AIProperties();
        for (SceneCode scene : SceneCode.values()) {
            AIProperties.SceneProperties config = new AIProperties.SceneProperties();
            config.setPrompt("prompts/chat/system.md");
            properties.getScenes().put(scene.getConfigKey(), config);
        }
        // yaml 兜底值：对话场景声明了温度与思维链，用来验证"后台留空则继承 yaml"
        properties.scene(SceneCode.CHAT).setTemperature(0.7);
        properties.scene(SceneCode.CHAT).setThinking(true);
        service = new AdminAiSceneServiceImpl(mapper, sceneBindings, modelMapper, runtimeConfig, properties);
    }

    private static AiSceneConfigEntity row(Long id, String sceneCode, Long configVersion) {
        AiSceneConfigEntity entity = new AiSceneConfigEntity();
        entity.setId(id);
        entity.setSceneCode(sceneCode);
        entity.setConfigVersion(configVersion);
        entity.setIsEnabled(1);
        return entity;
    }

    private static AdminAiSceneDtos.SaveRequest request() {
        AdminAiSceneDtos.SaveRequest request = new AdminAiSceneDtos.SaveRequest();
        request.setTemperature(new BigDecimal("0.30"));
        request.setMaxTokens(1024);
        return request;
    }

    private static AiModelSpec spec(String code, String source) {
        return new AiModelSpec(code, code, 1, null, null, "deepseek-chat",
                null, null, null, null, null, source, AiModelCapability.text());
    }

    @Test
    void 首次保存会插入一行且带乐观锁版本1() {
        // 第一次读：还没有配置行；保存后的回读：拿到刚插入的行（真实库里 insert 会回填自增 id）
        when(mapper.selectOne(any())).thenReturn(null, row(11L, "CHAT", 1L));
        when(runtimeConfig.specByModelCode(anyString())).thenReturn(spec("flash", "db"));
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "db"));
        AdminAiSceneDtos.SaveRequest request = request();
        request.setModelCode("flash");

        AdminAiSceneDtos.SceneRow saved = service.save("CHAT", request);

        ArgumentCaptor<AiSceneConfigEntity> captor = ArgumentCaptor.forClass(AiSceneConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("CHAT", captor.getValue().getSceneCode());
        assertEquals("flash", captor.getValue().getModelCode());
        assertEquals(1L, captor.getValue().getConfigVersion());
        assertEquals(1, captor.getValue().getIsEnabled());
        assertEquals("flash", saved.getEffectiveModelCode());
        assertTrue(saved.getConfigured());
        // 保存后本实例立即生效
        verify(sceneBindings).refreshNow();
    }

    @Test
    void 绑定了不存在的模型编码时保存被拒绝() {
        when(runtimeConfig.specByModelCode("not-exists")).thenReturn(null);
        AdminAiSceneDtos.SaveRequest request = request();
        request.setModelCode("not-exists");

        BizException error = assertThrows(BizException.class, () -> service.save("CHAT", request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("不存在或已停用"));
        verify(mapper, never()).insert(any(AiSceneConfigEntity.class));
    }

    @Test
    void 已有配置行时缺configVersion被拒绝() {
        when(mapper.selectOne(any())).thenReturn(row(3L, "CHAT", 2L));

        BizException error = assertThrows(BizException.class, () -> service.save("CHAT", request()));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("configVersion"));
    }

    @Test
    void 乐观锁冲突返回409() {
        when(mapper.selectOne(any())).thenReturn(row(3L, "CHAT", 2L));
        when(mapper.updateWithVersion(any(), eq(2L))).thenReturn(0);
        AdminAiSceneDtos.SaveRequest request = request();
        request.setConfigVersion(2L);

        BizException error = assertThrows(BizException.class, () -> service.save("CHAT", request));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(sceneBindings, never()).refreshNow();
    }

    @Test
    void 更新成功时把四个可覆盖字段都写进去() {
        when(mapper.selectOne(any())).thenReturn(row(3L, "CHAT", 2L));
        when(mapper.updateWithVersion(any(), eq(2L))).thenReturn(1);
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "yaml"));
        AdminAiSceneDtos.SaveRequest request = request();
        request.setConfigVersion(2L);
        request.setThinking(false);
        request.setStatus(1);

        AdminAiSceneDtos.SceneRow saved = service.save("CHAT", request);

        ArgumentCaptor<AiSceneConfigEntity> captor = ArgumentCaptor.forClass(AiSceneConfigEntity.class);
        verify(mapper).updateWithVersion(captor.capture(), eq(2L));
        assertEquals(new BigDecimal("0.30"), captor.getValue().getTemperature());
        assertEquals(1024, captor.getValue().getMaxTokens());
        assertEquals(Boolean.FALSE, captor.getValue().getThinking());
        // 列表里 yaml 兜底值要一并给出，前端才能解释"留空用的是什么"
        assertEquals(new BigDecimal("0.7"), saved.getYamlTemperature());
        assertEquals(Boolean.TRUE, saved.getYamlThinking());
    }

    @Test
    void 清空覆盖会把四个字段都置空() {
        when(mapper.selectOne(any())).thenReturn(row(3L, "CHAT", 5L));
        when(mapper.updateWithVersion(any(), eq(5L))).thenReturn(1);
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "yaml"));
        AdminAiSceneDtos.SaveRequest request = new AdminAiSceneDtos.SaveRequest();
        request.setClear(true);
        request.setConfigVersion(5L);

        service.save("CHAT", request);

        ArgumentCaptor<AiSceneConfigEntity> captor = ArgumentCaptor.forClass(AiSceneConfigEntity.class);
        verify(mapper).updateWithVersion(captor.capture(), eq(5L));
        assertNull(captor.getValue().getModelCode());
        assertNull(captor.getValue().getTemperature());
        assertNull(captor.getValue().getMaxTokens());
        assertNull(captor.getValue().getThinking());
    }

    @Test
    void 视觉场景可以保存模型编码且标记为生效() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(runtimeConfig.specByModelCode("flash")).thenReturn(spec("flash", "db"));
        when(runtimeConfig.visionSpec()).thenReturn(spec("qwen-vl", "db"));
        AdminAiSceneDtos.SaveRequest request = request();
        request.setModelCode("flash");

        AdminAiSceneDtos.SceneRow saved = service.save("chat-image", request);

        // 批次 2 起视觉场景也认绑定，所以这个字段对视觉场景同样生效
        assertTrue(saved.getModelCodeApplies());
        assertTrue(saved.getHint().contains("视觉"));
        assertEquals("qwen-vl", saved.getEffectiveModelCode());
    }

    @Test
    void 未知场景编码返回400() {
        BizException error = assertThrows(BizException.class,
                () -> service.save("CHAT_FUTURE", request()));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("未知的场景编码"));
    }

    @Test
    void 列表同时给出后台值与yaml兜底值与实际生效模型() {
        when(mapper.selectOne(any())).thenReturn(null);
        // ② 期新增 SUMMARY 场景（会话摘要）：它也走文本通道。本用例只关心 CHAT 与视觉场景，
        // 因此给文本通道一个兜底规格，避免新增场景把列表接口打挂（这本身也是个回归点）
        when(runtimeConfig.textSpec(any())).thenReturn(spec("flash", "yaml"));
        when(runtimeConfig.textSpec(SceneCode.CHAT)).thenReturn(spec("flash", "yaml"));
        when(runtimeConfig.visionSpec()).thenReturn(spec("qwen-vl", "db"));
        AiModelConfigEntity model = new AiModelConfigEntity();
        model.setModelCode("flash");
        model.setModelName("DeepSeek Flash");
        model.setProviderCode(1);
        model.setIsDefault(1);
        model.setIsEnabled(1);
        when(modelMapper.selectList(any())).thenReturn(List.of(model));

        AdminAiSceneDtos.SceneList list = service.list();

        // ② 期 SUMMARY、③ 期 MEMORY、社区 AI 审核新增 MODERATION，共 7 个
        assertEquals(7, list.getRecords().size());
        assertEquals(1, list.getModels().size());
        assertEquals("DeepSeek Flash", list.getModels().get(0).getName());
        assertEquals("DeepSeek（自研 SDK）", list.getModels().get(0).getProviderName());

        AdminAiSceneDtos.SceneRow chat = list.getRecords().stream()
                .filter(row -> "CHAT".equals(row.getSceneCode())).findFirst().orElseThrow();
        assertEquals("flash", chat.getEffectiveModelCode());
        assertEquals("yaml", chat.getEffectiveModelSource());
        assertFalse(chat.getConfigured());
        assertTrue(chat.getModelCodeApplies());

        AdminAiSceneDtos.SceneRow meal = list.getRecords().stream()
                .filter(row -> "MEAL_ANALYSIS".equals(row.getSceneCode())).findFirst().orElseThrow();
        assertEquals("qwen-vl", meal.getEffectiveModelCode(), "视觉场景的生效模型必须来自视觉通道");
        assertTrue(meal.getModelCodeApplies(), "批次 2 起视觉场景的绑定也生效");
    }
}
