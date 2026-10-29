package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.config.AiModelType;
import com.threadfit.glucopilot.ai.model.dto.AdminAiModelDtos;
import com.threadfit.glucopilot.ai.model.entity.AiModelConfigEntity;
import com.threadfit.glucopilot.ai.model.mapper.AiModelConfigMapper;
import com.threadfit.glucopilot.ai.model.runtime.AiModelCipher;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.model.service.impl.AdminAiModelServiceImpl;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台模型配置的<b>用途</b>（{@code model_type}，V45 引入）行为。
 * <p>
 * 这里只钉三件容易回退的事：
 * <ol>
 *     <li>旧前端不传用途时必须落成 TEXT，而不是 NULL 或报错（滚动升级期间老页面还要能用）；</li>
 *     <li>标默认时只能清掉<b>同用途</b>的旧默认——把视觉模型设成默认不该顺手摘掉文本默认；</li>
 *     <li>非法用途是 400（参数错）而不是 500（系统错），否则管理端只会提示"服务器异常"。</li>
 * </ol>
 */
class AdminAiModelServiceTest {

    private static final String MASTER_KEY = "0123456789abcdef0123456789abcdef";

    private AiModelConfigMapper mapper;

    private AiRuntimeConfigService runtimeConfig;

    private AdminAiModelServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        runtimeConfig = mock(AiRuntimeConfigService.class);
        when(runtimeConfig.snapshot()).thenReturn(snapshot());
        service = new AdminAiModelServiceImpl(mapper, new AiModelCipher(MASTER_KEY),
                runtimeConfig, mock(ChatModelFactory.class));
    }

    @Test
    void 新增时用途留空按文本落库() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request(null, false));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("TEXT", captor.getValue().getModelType(),
                "旧前端不传用途时必须是 TEXT：NULL 会让「历史行」与「看作文本」两套语义并存");
    }

    @Test
    void 视觉模型标默认只清同用途的旧默认() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request("VISION", true));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("VISION", captor.getValue().getModelType());
        // 关键：第二个参数是用途过滤条件。少了它，设视觉默议会把文本默认一起摘掉，
        // 于是"刚设完默认，AI 却没有默认模型了"
        verify(mapper).clearOtherDefaults(7L, "VISION");
    }

    @Test
    void 文本模型标默认只清同用途的旧默认() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request("text", true));

        verify(mapper).clearOtherDefaults(7L, "TEXT");
    }

    @Test
    void 非法用途应是参数错误而不是系统异常() {
        BizException error = assertThrows(BizException.class,
                () -> service.create(request("IMAGE", false)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("用途"), "提示里要说明是用途字段错：" + error.getMessage());
    }

    @Test
    void 列表应回显用途与用途文字() {
        AiModelConfigEntity vision = row(11L, "qwen-vl-internal", "VISION");
        when(mapper.selectList(any())).thenReturn(List.of(vision));

        AdminAiModelDtos.ModelList list = service.list();

        assertEquals("VISION", list.getRecords().getFirst().getModelType());
        assertEquals("视觉", list.getRecords().getFirst().getModelTypeText());
    }

    @Test
    void 历史没有用途的行回显成文本() {
        AiModelConfigEntity legacy = row(12L, "deepseek-v4-pro", null);
        when(mapper.selectList(any())).thenReturn(List.of(legacy));

        AdminAiModelDtos.ModelList list = service.list();

        assertEquals("TEXT", list.getRecords().getFirst().getModelType());
        assertEquals("文本", list.getRecords().getFirst().getModelTypeText());
    }

    @Test
    void 编辑时不传用途应保持原用途() {
        AiModelConfigEntity vision = row(11L, "qwen-vl-internal", "VISION");
        vision.setConfigVersion(3L);
        when(mapper.selectById(11L)).thenReturn(vision);
        when(mapper.updateWithVersion(any(AiModelConfigEntity.class), any(Long.class), any(), any(), any()))
                .thenReturn(1);

        AdminAiModelDtos.SaveRequest request = request(null, false);
        request.setConfigVersion(3);
        service.update(11L, request);

        // 旧前端/外部脚本的 PUT 不带用途：必须保持 VISION，不能被归一成 TEXT
        verify(mapper).updateWithVersion(
                org.mockito.ArgumentMatchers.argThat(e -> "VISION".equals(e == null ? null : e.getModelType())),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
    }

    // ---------------------------------------------------------------- 构造

    private void stubInsert(long generatedId) {
        AiModelConfigEntity[] saved = new AiModelConfigEntity[1];
        when(mapper.insert(any(AiModelConfigEntity.class))).thenAnswer(invocation -> {
            AiModelConfigEntity entity = invocation.getArgument(0);
            entity.setId(generatedId);
            saved[0] = entity;
            return 1;
        });
        when(mapper.selectById(generatedId)).thenAnswer(invocation -> saved[0]);
    }

    private static AdminAiModelDtos.SaveRequest request(String modelType, boolean asDefault) {
        AdminAiModelDtos.SaveRequest request = new AdminAiModelDtos.SaveRequest();
        request.setModelCode("qwen-vl-internal");
        request.setModelName("视觉模型");
        request.setProviderCode(2);
        request.setModelEndpoint("qwen-vl-max");
        request.setModelType(modelType);
        request.setIsEnabled(1);
        request.setIsDefault(asDefault ? 1 : 0);
        return request;
    }

    private static AiModelConfigEntity row(long id, String code, String modelType) {
        AiModelConfigEntity entity = new AiModelConfigEntity();
        entity.setId(id);
        entity.setModelCode(code);
        entity.setModelName(code);
        entity.setProviderCode(2);
        entity.setModelType(modelType);
        entity.setModelEndpoint(code + "-endpoint");
        entity.setTemperature(new BigDecimal("0.20"));
        entity.setMaxTokens(512);
        entity.setTimeoutMs(60000);
        entity.setIsEnabled(1);
        entity.setIsDefault(0);
        entity.setConfigVersion(1L);
        entity.setGmtModified(LocalDateTime.now());
        return entity;
    }

    private static AiRuntimeConfigService.Snapshot snapshot() {
        AiModelSpec yamlText = new AiModelSpec("deepseek-v4-flash", "DeepSeek 文本模型（yaml）", 1,
                "https://api.deepseek.com", "sk-yaml-text", "deepseek-v4-flash",
                0.7, null, 2048, null, Duration.ofSeconds(60), "yaml", AiModelType.TEXT);
        AiModelSpec yamlVision = new AiModelSpec("qwen3-vl-plus", "视觉模型（yaml）", 2,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-yaml-vision", "qwen3-vl-plus",
                0.2, null, 512, null, Duration.ofSeconds(60), "yaml", AiModelType.VISION);
        Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
        byCode.put(yamlText.modelCode(), yamlText);
        byCode.put(yamlVision.modelCode(), yamlVision);
        return new AiRuntimeConfigService.Snapshot(1L, byCode, new HashMap<>(),
                yamlText, yamlVision, yamlText, "yaml 兜底", null, "yaml 兜底", 0);
    }
}
