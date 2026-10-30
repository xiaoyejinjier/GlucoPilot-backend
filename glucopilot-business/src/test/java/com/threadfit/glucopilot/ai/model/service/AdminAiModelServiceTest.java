package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiModelCapability;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台模型配置的<b>能力</b>（{@code capability}，V46 由 V45 的 {@code model_type} 升级而来）行为。
 * <p>
 * 这里只钉几件容易回退的事：
 * <ol>
 *     <li>旧前端不传能力时必须落成 {@code text}，而不是 NULL 或报错（滚动升级期间老页面还要能用）；</li>
 *     <li>标默认时只能清掉<b>同一个默认池</b>的旧默认——把视觉模型设成默认不该顺手摘掉文本默认；</li>
 *     <li>非法能力是 400（参数错）而不是 500（系统错），否则管理端只会提示"服务器异常"；</li>
 *     <li>能力集合要按 token 原样保存（{@code text,tool} 不能被截成 {@code text}）。</li>
 * </ol>
 */
class AdminAiModelServiceTest {

    private static final String MASTER_KEY = "0123456789abcdef0123456789abcdef";

    private AiModelConfigMapper mapper;

    private AiRuntimeConfigService runtimeConfig;

    private ChatModelFactory chatModelFactory;

    private AIProperties aiProperties;

    private AdminAiModelServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        runtimeConfig = mock(AiRuntimeConfigService.class);
        when(runtimeConfig.snapshot()).thenReturn(snapshot());
        chatModelFactory = mock(ChatModelFactory.class);
        // 默认让探针通过：绝大多数用例关心的是能力/默认池，不想每次都摆一遍连通性
        when(chatModelFactory.test(any(), any())).thenReturn(
                new ChatModelFactory.ConnectivityResult(true, "OK", 12, "qwen-vl-max", "蓝"));
        aiProperties = new AIProperties();
        service = new AdminAiModelServiceImpl(mapper, new AiModelCipher(MASTER_KEY),
                runtimeConfig, chatModelFactory, aiProperties);
    }

    @Test
    void 新增时能力留空按文本落库() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request(null, false));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("text", captor.getValue().getCapability(),
                "旧前端不传能力时必须是 text：NULL 会让「历史行」与「只有文本」两套语义并存");
    }

    @Test
    void 视觉模型标默认只清同池的旧默认() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request("vision,stream", true));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("vision,stream", captor.getValue().getCapability());
        // 关键：第二个参数是默认池过滤条件。少了它，设视觉默议会把文本默认一起摘掉，
        // 于是"刚设完默认，AI 却没有默认模型了"
        verify(mapper).clearOtherDefaults(7L, "vision");
    }

    @Test
    void 文本模型标默认只清同池的旧默认() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request("text", true));

        verify(mapper).clearOtherDefaults(7L, "text");
    }

    @Test
    void 同时具备文本与视觉能力的模型归视觉池() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        // 多模态模型也能当文本模型用，但它只有一个 is_default，必须只归一个池；
        // 含 vision 即视觉池（文本场景仍可通过场景绑定显式选中它）。
        service.create(request("text,vision,stream", true));

        verify(mapper).clearOtherDefaults(7L, "vision");
    }

    @Test
    void 能力集合要按token原样保存() {
        stubInsert(7L);
        when(mapper.selectCount(any())).thenReturn(0L);

        // 顺序与大小写都归一，但 tool 不能被吞掉
        service.create(request(" STREAM , Tool ,TEXT", false));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("text,tool,stream", captor.getValue().getCapability());
    }

    @Test
    void 非法能力应是参数错误而不是系统异常() {
        BizException error = assertThrows(BizException.class,
                () -> service.create(request("image", false)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("能力"), "提示里要说明是能力字段错：" + error.getMessage());
        assertTrue(error.getMessage().contains("text"), "提示里要给出合法取值：" + error.getMessage());
    }

    @Test
    void 列表应回显能力与能力文字() {
        AiModelConfigEntity vision = row(11L, "qwen-vl-internal", "vision,stream");
        when(mapper.selectList(any())).thenReturn(List.of(vision));

        AdminAiModelDtos.ModelList list = service.list();

        assertEquals("vision,stream", list.getRecords().getFirst().getCapability());
        assertEquals("视觉 / 流式", list.getRecords().getFirst().getCapabilityText());
    }

    @Test
    void 历史没有能力的行回显成文本() {
        AiModelConfigEntity legacy = row(12L, "deepseek-v4-pro", null);
        when(mapper.selectList(any())).thenReturn(List.of(legacy));

        AdminAiModelDtos.ModelList list = service.list();

        assertEquals("text", list.getRecords().getFirst().getCapability());
        assertEquals("文本", list.getRecords().getFirst().getCapabilityText());
    }

    @Test
    void 编辑时不传能力应保持原能力() {
        AiModelConfigEntity vision = row(11L, "qwen-vl-internal", "vision,stream");
        vision.setConfigVersion(3L);
        when(mapper.selectById(11L)).thenReturn(vision);
        when(mapper.updateWithVersion(any(AiModelConfigEntity.class), any(Long.class), any(), any(), any()))
                .thenReturn(1);

        AdminAiModelDtos.SaveRequest request = request(null, false);
        request.setConfigVersion(3);
        service.update(11L, request);

        // 旧前端/外部脚本的 PUT 不带能力：必须保持 vision,stream，不能被归一成 text
        verify(mapper).updateWithVersion(
                org.mockito.ArgumentMatchers.argThat(e -> "vision,stream".equals(e == null ? null : e.getCapability())),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
    }

    // ---------------------------------------------------------------- 保存前连通性校验

    @Test
    void 连通性校验失败时应拒绝保存且不落库() {
        when(chatModelFactory.test(any(), any())).thenReturn(
                new ChatModelFactory.ConnectivityResult(false, "AUTH", 30, null, "401 Unauthorized"));
        when(mapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
                () -> service.create(request("vision,stream", false)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("连通性"), error.getMessage());
        assertTrue(error.getMessage().contains("AUTH"), "要把分类带给前端，管理员才知道改哪个字段：" + error.getMessage());
        // 关键：没校验通过就绝不能落库，否则后台会出现一行"看起来正常、一用就 401"的配置
        verify(mapper, never()).insert(any(AiModelConfigEntity.class));
    }

    @Test
    void 勾选跳过校验但没填原因应直接拒绝() {
        AdminAiModelDtos.SaveRequest request = request("vision,stream", false);
        request.setSkipVerify(true);
        when(mapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("原因"), error.getMessage());
        verify(chatModelFactory, never()).test(any(), any());
        verify(mapper, never()).insert(any(AiModelConfigEntity.class));
    }

    @Test
    void 勾选跳过并填原因应落库且留下痕迹() {
        AdminAiModelDtos.SaveRequest request = request("vision,stream", false);
        request.setSkipVerify(true);
        request.setVerifySkipReason("  厂商还没开通，先录配置  ");
        when(mapper.selectCount(any())).thenReturn(0L);
        stubInsert(7L);

        service.create(request);

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("厂商还没开通，先录配置", captor.getValue().getVerifySkippedReason(),
                "原因要落库（去空白），否则事后没人知道这行为什么没测过");
        // 既然明确跳过了，就不该再发探针——否则"跳过"等于没跳过
        verify(chatModelFactory, never()).test(any(), any());
    }

    @Test
    void 校验通过时不应留下跳过原因() {
        when(mapper.selectCount(any())).thenReturn(0L);
        stubInsert(7L);

        service.create(request("vision,stream", false));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getVerifySkippedReason());
        verify(chatModelFactory).test(any(), any());
    }

    @Test
    void 服务端关闭校验时不发探针但说明原因() {
        aiProperties.getModel().setVerifyOnSave(false);
        when(mapper.selectCount(any())).thenReturn(0L);
        stubInsert(7L);

        service.create(request("vision,stream", false));

        ArgumentCaptor<AiModelConfigEntity> captor = ArgumentCaptor.forClass(AiModelConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertNotNull(captor.getValue().getVerifySkippedReason());
        assertTrue(captor.getValue().getVerifySkippedReason().contains("verify-on-save"),
                "要写明是服务端关掉的，不然会被误读成有人手工跳过");
        verify(chatModelFactory, never()).test(any(), any());
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

    private static AdminAiModelDtos.SaveRequest request(String capability, boolean asDefault) {
        AdminAiModelDtos.SaveRequest request = new AdminAiModelDtos.SaveRequest();
        request.setModelCode("qwen-vl-internal");
        request.setModelName("视觉模型");
        request.setProviderCode(2);
        request.setModelEndpoint("qwen-vl-max");
        request.setCapability(capability);
        request.setIsEnabled(1);
        request.setIsDefault(asDefault ? 1 : 0);
        return request;
    }

    private static AiModelConfigEntity row(long id, String code, String capability) {
        AiModelConfigEntity entity = new AiModelConfigEntity();
        entity.setId(id);
        entity.setModelCode(code);
        entity.setModelName(code);
        entity.setProviderCode(2);
        entity.setCapability(capability);
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
                0.7, null, 2048, null, Duration.ofSeconds(60), "yaml",
                AiModelCapability.parse("text,tool,stream"));
        AiModelSpec yamlVision = new AiModelSpec("qwen3-vl-plus", "视觉模型（yaml）", 2,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-yaml-vision", "qwen3-vl-plus",
                0.2, null, 512, null, Duration.ofSeconds(60), "yaml", AiModelCapability.visionDefaults());
        Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
        byCode.put(yamlText.modelCode(), yamlText);
        byCode.put(yamlVision.modelCode(), yamlVision);
        return new AiRuntimeConfigService.Snapshot(1L, byCode, new HashMap<>(),
                yamlText, yamlVision, yamlText, "yaml 兜底", null, "yaml 兜底", 0);
    }
}
