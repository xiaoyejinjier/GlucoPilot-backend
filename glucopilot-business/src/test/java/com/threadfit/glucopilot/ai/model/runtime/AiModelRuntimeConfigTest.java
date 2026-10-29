package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiChannelProperties;
import com.threadfit.glucopilot.ai.config.AiModelType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.model.entity.AiModelConfigEntity;
import com.threadfit.glucopilot.ai.model.mapper.AiModelConfigMapper;
import com.threadfit.glucopilot.ai.model.runtime.AiModelCipher;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.model.runtime.DynamicChatModel;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.model.service.impl.AiRuntimeConfigServiceImpl;

/**
 * 模型配置后台化的核心不变量。
 * <p>
 * 这一层的风险不在"能不能读到配置"，而在几处**错了不会报错、只会静默走偏**的地方，所以逐条钉住：
 * <ol>
 *   <li>密钥：加密能往返、打码不泄漏、主密钥缺失时拒绝保存而不是降级成明文；</li>
 *   <li>回落：解密失败的行整行跳过（而不是带着未知凭证发请求）、加载失败沿用上一份快照；</li>
 *   <li>版本：按<b>内容</b>判定，配置没变时不递增——否则 TTL 刷新会反复清掉模型缓存；</li>
 *   <li>按请求声明的模型名取凭据：避免"用 A 的密钥调 B 的模型"；</li>
 *   <li>与 yaml 等价时复用容器里的模型，避免静默失去重试等自动装配行为。</li>
 * </ol>
 */
class AiModelRuntimeConfigTest {

    private static final String MASTER_KEY = "0123456789abcdef0123456789abcdef";

    // ---------------------------------------------------------------- 密钥

    @Test
    void 密钥应能加密后原样解回且不打码泄漏() {
        AiModelCipher cipher = new AiModelCipher(MASTER_KEY);
        String plain = "sk-" + "abcdefgh-placeholder-2345";

        String encrypted = cipher.encrypt(plain);

        assertNotEquals(plain, encrypted, "落库的必须是密文");
        assertFalse(encrypted.contains(plain), "密文里不应出现明文密钥");
        assertEquals(plain, cipher.decryptOrNull(encrypted));

        String masked = AiModelCipher.mask(plain);
        assertEquals("sk-****2345", masked);
        assertFalse(masked.contains("abcdefgh"), "掩码不能泄漏中间部分");
    }

    @Test
    void 同一明文两次加密结果应不同_GCM随机IV() {
        AiModelCipher cipher = new AiModelCipher(MASTER_KEY);
        assertNotEquals(cipher.encrypt("sk-same-value"), cipher.encrypt("sk-same-value"));
    }

    @Test
    void 主密钥缺失时应拒绝保存密钥而不是降级成明文() {
        AiModelCipher noKey = new AiModelCipher("");

        assertFalse(noKey.available());
        assertThrows(RuntimeException.class, () -> noKey.encrypt("sk-whatever"));
        assertNull(noKey.decryptOrNull("anything"), "无法解密时应返回 null，由上层回落 yaml");
    }

    @Test
    void 换错主密钥时解密失败而不是解出乱码() {
        String encrypted = new AiModelCipher(MASTER_KEY).encrypt("sk-secret-value");
        AiModelCipher other = new AiModelCipher("ffffffffffffffffffffffffffffffff");
        assertNull(other.decryptOrNull(encrypted), "GCM 应校验完整性，错误主密钥解不出结果");
    }

    // ---------------------------------------------------------------- 运行时装载

    @Test
    void 应优先使用DB配置并在缺省时回落yaml() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(
                row("deepseek-v4-flash", 1, null),
                visionRow("qwen3-vl-plus")));

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));
        AiRuntimeConfigService.Snapshot snapshot = service.snapshot();

        // DB 里登记的默认文本模型生效，且密钥留空时继承 yaml 的密钥
        assertEquals("db", snapshot.resolveText("deepseek-v4-flash").source());
        assertEquals("sk-yaml-text", snapshot.resolveText("deepseek-v4-flash").apiKey());
        // 视觉通道：DB 里有 qwen3-vl-plus 就用它（同样继承 yaml 密钥）
        assertEquals("db", snapshot.resolveVisionDefault().source());
        assertEquals("sk-yaml-vision", snapshot.resolveVisionDefault().apiKey());
        // 未登记的编码回落 yaml 规格
        assertEquals("yaml", snapshot.resolveText("unknown-model").source());
        assertEquals("deepseek-v4-flash", snapshot.resolveText("unknown-model").modelEndpoint());
        // 文本通道默认走"默认标记"那一行
        assertEquals("db", snapshot.resolveTextDefault().source());
        assertEquals("deepseek-v4-flash", snapshot.resolveTextDefault().modelCode());
        assertEquals("默认标记", snapshot.textDefaultReason());
    }

    // ------------------------------------------------- 文本默认模型的选人判据
    // 2026-10-13 的反馈：停用 flash、启用新建的 pro，实际调的还是 flash。
    // 根因是通道默认写成"yaml 里那个模型名的解析"——停用后 byCode 里少了 flash 的 DB 条目，
    // 却恰好命中 yaml 兜底条目（它也叫 flash），于是"配置改了、调用没变"且不报错。

    @Test
    void 默认模型被停用后应让位给新启用的模型而不是退回yaml() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        // flash 已停用（selectEnabled 不会返回它），只剩新启用的 pro 与视觉行，且 pro 没标默认
        when(mapper.selectEnabled()).thenReturn(List.of(
                noDefault(row("deepseek-v4-pro", 1, null)),
                visionRow("qwen3-vl-plus")));

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));
        AiRuntimeConfigService.Snapshot snapshot = service.snapshot();

        assertEquals("deepseek-v4-pro", snapshot.resolveTextDefault().modelCode(), "不能再退回 yaml 的 flash");
        assertEquals("db", snapshot.resolveTextDefault().source());
        assertEquals("唯一启用的文本模型", snapshot.textDefaultReason());
        // 视觉行不能被当成文本默认
        assertNotEquals("qwen3-vl-plus", snapshot.resolveTextDefault().modelCode());
    }

    @Test
    void 多行启用且都没标默认时应取最近修改的那一行() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        AiModelConfigEntity flash = noDefault(row("deepseek-v4-flash", 1, null));
        flash.setGmtModified(LocalDateTime.now().minusHours(3));
        AiModelConfigEntity pro = noDefault(row("deepseek-v4-pro", 1, null));
        pro.setGmtModified(LocalDateTime.now());

        when(mapper.selectEnabled()).thenReturn(List.of(flash, pro));

        AiRuntimeConfigService.Snapshot snapshot = service(mapper, new AiModelCipher(MASTER_KEY)).snapshot();

        assertEquals("deepseek-v4-pro", snapshot.resolveTextDefault().modelCode());
        assertEquals("最近修改的文本模型", snapshot.textDefaultReason());
    }

    @Test
    void 有默认标记时应压过最近修改的行() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        AiModelConfigEntity flash = row("deepseek-v4-flash", 1, null); // is_default=1
        flash.setGmtModified(LocalDateTime.now().minusDays(1));
        AiModelConfigEntity pro = noDefault(row("deepseek-v4-pro", 1, null));
        pro.setGmtModified(LocalDateTime.now());
        when(mapper.selectEnabled()).thenReturn(List.of(flash, pro));

        AiRuntimeConfigService.Snapshot snapshot = service(mapper, new AiModelCipher(MASTER_KEY)).snapshot();

        assertEquals("deepseek-v4-flash", snapshot.resolveTextDefault().modelCode(), "显式默认标记优先");
        assertEquals("默认标记", snapshot.textDefaultReason());
    }

    @Test
    void 一个文本候选都没有时应回落yaml() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(visionRow("qwen3-vl-plus")));

        AiRuntimeConfigService.Snapshot snapshot = service(mapper, new AiModelCipher(MASTER_KEY)).snapshot();

        assertEquals("yaml", snapshot.resolveTextDefault().source());
        assertEquals("deepseek-v4-flash", snapshot.resolveTextDefault().modelEndpoint());
        assertEquals("yaml 兜底", snapshot.textDefaultReason());
    }

    @Test
    void 视觉默认应能选中端点与yaml不同的视觉模型() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        // 批次 2 之前视觉行只能靠"端点等于 ai.vision.model"被认出来，于是运营新增一个
        // 端点名不同的视觉模型（如 qwen-vl-max），后台怎么点都不会生效
        AiModelConfigEntity vision = visionRow("qwen-vl-internal");
        vision.setModelEndpoint("qwen-vl-max");
        when(mapper.selectEnabled()).thenReturn(List.of(vision));

        AiRuntimeConfigService.Snapshot snapshot = service(mapper, new AiModelCipher(MASTER_KEY)).snapshot();

        assertEquals("db", snapshot.resolveVisionDefault().source());
        assertEquals("qwen-vl-internal", snapshot.resolveVisionDefault().modelCode());
        assertEquals("默认标记", snapshot.visionDefaultReason());
    }

    @Test
    void 视觉行标了默认也不能抢走文本默认() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        AiModelConfigEntity vision = visionRow("qwen-vl-internal"); // is_default=1
        when(mapper.selectEnabled()).thenReturn(List.of(
                noDefault(row("deepseek-v4-pro", 1, null)), vision));

        AiRuntimeConfigService.Snapshot snapshot = service(mapper, new AiModelCipher(MASTER_KEY)).snapshot();

        // 视觉行参与的是视觉默认判定；文本默认只能在文本行里选，没有就是 yaml
        assertEquals("deepseek-v4-pro", snapshot.resolveTextDefault().modelEndpoint());
        assertEquals("qwen-vl-internal", snapshot.resolveVisionDefault().modelCode());
    }

    @Test
    void 没有视觉行时视觉默认应回落yaml() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(row("deepseek-v4-pro", 1, null)));

        AiRuntimeConfigService.Snapshot snapshot = service(mapper, new AiModelCipher(MASTER_KEY)).snapshot();

        assertEquals("yaml", snapshot.resolveVisionDefault().source());
        assertEquals("qwen3-vl-plus", snapshot.resolveVisionDefault().modelEndpoint());
        assertEquals("yaml 兜底", snapshot.visionDefaultReason());
    }

    @Test
    void 视觉的yaml规格也应进索引否则按编码引用全是空转() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of());

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));

        // 视觉 yaml 只有 ai.vision.model 一个来源；不进 byCode 的话，任何「按编码引用」
        // （场景绑定、后台回填 model_code）对视觉通道都查不到东西
        AiModelSpec vision = service.specByModelCode("qwen3-vl-plus");
        assertNotNull(vision, "视觉 yaml 规格必须能按编码查到");
        assertEquals("yaml", vision.source());
        assertEquals("qwen3-vl-plus", vision.modelEndpoint());
        // 请求里声明视觉模型名时也要能反查到凭据（byName）
        assertEquals("sk-yaml-vision", service.specByModelName("qwen3-vl-plus").apiKey());
    }

    @Test
    void 文本通道的超时应来自通道配置而不是写死六十秒() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of());
        AiChannelProperties channels = channels();
        channels.getText().setTimeoutSeconds(15);

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY), channels);

        assertEquals(Duration.ofSeconds(15), service.snapshot().yamlText().timeout(),
                "yaml 文本规格的超时必须读 ai.channels.text.timeout-seconds");
        // 视觉通道走自己的配置，互不影响
        assertEquals(Duration.ofSeconds(60), service.snapshot().yamlVision().timeout());
    }
    @Test
    void 文本通道默认不应被yaml里同名的模型名钉死() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(row("deepseek-v4-pro", 1, null), visionRow("qwen3-vl-plus")));

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));
        AiRuntimeConfigService.Snapshot snapshot = service.snapshot();

        // 旧实现（resolveText(yamlText.modelCode())）在这里会返回 yaml 的 flash 规格
        assertEquals("yaml", snapshot.resolveText("deepseek-v4-flash").source());
        assertEquals("db", snapshot.resolveTextDefault().source());
        assertEquals("deepseek-v4-pro", snapshot.resolveTextDefault().modelEndpoint());
    }


    @Test
    void 密钥解不开的行应整行跳过而不是带着未知凭证发请求() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        // 用另一个主密钥加密 → 当前实例解不开
        String foreign = new AiModelCipher("ffffffffffffffffffffffffffffffff").encrypt("sk-foreign");
        when(mapper.selectEnabled()).thenReturn(List.of(visionRow("qwen3-vl-plus", foreign)));

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));
        AiRuntimeConfigService.Snapshot snapshot = service.snapshot();

        assertEquals(1, snapshot.skipped(), "解不开密钥的行应被跳过");
        assertEquals("yaml", snapshot.resolveVisionDefault().source(), "视觉通道回落到 yaml 配置");
        assertEquals("sk-yaml-vision", snapshot.resolveVisionDefault().apiKey());
    }

    @Test
    void 配置内容不变时版本号不应变化() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenReturn(List.of(row("deepseek-v4-flash", 1, null)));

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));
        long first = service.snapshot().version();

        // TTL 到期会重新加载，但内容没变 → 版本必须一样，否则 DynamicChatModel 会反复清缓存
        service.refreshNow();
        assertEquals(first, service.snapshot().version());

        // 内容变了（换了模型名）→ 版本必须变
        AiModelConfigEntity changed = row("deepseek-v4-flash", 1, null);
        changed.setModelEndpoint("deepseek-chat");
        when(mapper.selectEnabled()).thenReturn(List.of(changed));
        service.refreshNow();
        assertNotEquals(first, service.snapshot().version());
    }

    @Test
    void 加载抛异常时应用yaml而不是把AI打挂() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectEnabled()).thenThrow(new IllegalStateException("库抖了"));

        AiRuntimeConfigService service = service(mapper, new AiModelCipher(MASTER_KEY));

        assertEquals("yaml", service.snapshot().resolveText(null).source());
        assertEquals("deepseek-v4-flash", service.snapshot().resolveText(null).modelEndpoint());
        // 加载失败时通道默认也必须是可用的 yaml 规格，而不是 null（否则首条消息就会 NPE）
        assertEquals("yaml", service.snapshot().resolveTextDefault().source());
        assertEquals("yaml 兜底", service.snapshot().textDefaultReason());
    }

    @Test
    void 文本通道默认应跟随快照的文本默认而不是yaml规格() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        AiModelSpec yamlSpec = spec("deepseek-v4-flash", "deepseek-v4-flash", "sk-yaml", "yaml");
        AiModelSpec proSpec = spec("deepseek-v4-pro", "deepseek-v4-pro", "sk-pro", "db");
        when(runtime.snapshot()).thenReturn(snapshotOf(proSpec, yamlSpec));
        when(factory.build(any())).thenReturn(mock(ChatModel.class));

        DynamicChatModel delegate = new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                null);

        delegate.call(new Prompt("hello"));

        org.mockito.ArgumentCaptor<AiModelSpec> captor = org.mockito.ArgumentCaptor.forClass(AiModelSpec.class);
        org.mockito.Mockito.verify(factory).build(captor.capture());
        assertEquals("deepseek-v4-pro", captor.getValue().modelCode(), "不能再用 yaml 里同名的旧模型");
    }

    // ---------------------------------------------------------------- 委托

    @Test
    void 请求里声明的模型名应决定用哪套凭据() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        AiModelSpec specA = spec("model-a", "endpoint-a", "sk-a", "db");
        AiModelSpec specB = spec("model-b", "endpoint-b", "sk-b", "db");
        when(runtime.snapshot()).thenReturn(snapshotOf(specA, specB, spec("deepseek-v4-flash", "deepseek-v4-flash", "sk-yaml", "yaml")));
        ChatModel built = mock(ChatModel.class);
        when(factory.build(any())).thenReturn(built);

        DynamicChatModel delegate = new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                null);

        // options 里声明了 endpoint-b → 必须用 B 的规格构造，而不是通道默认的 A
        ChatOptionsHolder holder = new ChatOptionsHolder();
        delegate.call(new Prompt("hello", holder.options("endpoint-b")));

        org.mockito.ArgumentCaptor<AiModelSpec> captor = org.mockito.ArgumentCaptor.forClass(AiModelSpec.class);
        org.mockito.Mockito.verify(factory).build(captor.capture());
        assertEquals("model-b", captor.getValue().modelCode());
        assertEquals("sk-b", captor.getValue().apiKey());
    }

    @Test
    void 与yaml等价的DB配置应复用容器里的模型() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        AiModelSpec yamlSpec = spec("deepseek-v4-flash", "deepseek-v4-flash", "sk-yaml", "yaml");
        AiModelSpec dbEquivalent = spec("deepseek-v4-flash", "deepseek-v4-flash", "sk-yaml", "db");
        when(runtime.snapshot()).thenReturn(snapshotOf(dbEquivalent, yamlSpec));
        ChatModel yamlModel = mock(ChatModel.class);

        DynamicChatModel delegate = new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                () -> yamlModel);

        delegate.call(new Prompt("hello"));

        // 等价 → 用容器里自动装配的那份（保留 RetryTemplate 与观测），不新建
        org.mockito.Mockito.verify(factory, org.mockito.Mockito.never()).build(any());
    }

    @Test
    void 与yaml不同的DB配置应走工厂构造() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        AiModelSpec dbSpec = spec("deepseek-v4-flash", "deepseek-chat", "sk-db", "db");
        when(runtime.snapshot()).thenReturn(snapshotOf(dbSpec, spec("deepseek-v4-flash", "deepseek-v4-flash", "sk-yaml", "yaml")));
        when(factory.build(any())).thenReturn(mock(ChatModel.class));

        DynamicChatModel delegate = new DynamicChatModel("text", runtime, factory,
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                () -> mock(ChatModel.class));

        delegate.call(new Prompt("hello"));

        org.mockito.Mockito.verify(factory).build(any());
    }

    // ---------------------------------------------------------------- 错误归类

    @Test
    void 连通性测试的错误应按该改哪个字段归类() {
        assertEquals("AUTH", ChatModelFactory.classify(new RuntimeException(
                "401 Unauthorized: Invalid API key provided")));
        assertEquals("MODEL_NOT_FOUND", ChatModelFactory.classify(new RuntimeException(
                "com.openai.errors.NotFoundException: 404: Model not exist.")));
        assertEquals("RATE_LIMIT", ChatModelFactory.classify(new RuntimeException("429 Too Many Requests")));
        assertEquals("TIMEOUT", ChatModelFactory.classify(new RuntimeException("request timed out")));
        assertEquals("NETWORK", ChatModelFactory.classify(new RuntimeException(
                "java.net.ConnectException: Connection refused")));
        assertEquals("UNKNOWN", ChatModelFactory.classify(new IllegalStateException("something else")));
    }

    // ---------------------------------------------------------------- 辅助

    private AiRuntimeConfigService service(AiModelConfigMapper mapper, AiModelCipher cipher) {
        return service(mapper, cipher, channels());
    }

    private AiRuntimeConfigService service(AiModelConfigMapper mapper, AiModelCipher cipher,
                                           AiChannelProperties channels) {
        return new AiRuntimeConfigServiceImpl(mapper, cipher, new AIProperties(), channels);
    }

    /** 两个通道的 yaml 侧取值：与 application.yaml 里的 ai.channels.* 同形。 */
    static AiChannelProperties channels() {
        AiChannelProperties channels = new AiChannelProperties();
        channels.getText().setBaseUrl("https://api.deepseek.com");
        channels.getText().setApiKey("sk-yaml-text");
        channels.getText().setModel("deepseek-v4-flash");
        channels.getText().setTemperature(0.7);
        channels.getText().setMaxTokens(2048);
        channels.getText().setTimeoutSeconds(60);
        channels.getVision().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        channels.getVision().setApiKey("sk-yaml-vision");
        channels.getVision().setModel("qwen3-vl-plus");
        channels.getVision().setTemperature(0.2);
        channels.getVision().setMaxTokens(512);
        channels.getVision().setTimeoutSeconds(60);
        return channels;
    }

    private static AiModelConfigEntity row(String modelCode, int provider, String cipherText) {
        AiModelConfigEntity entity = new AiModelConfigEntity();
        entity.setId(1L);
        entity.setModelCode(modelCode);
        entity.setModelName(modelCode);
        entity.setProviderCode(provider);
        entity.setModelEndpoint(modelCode);
        entity.setApiKeyCipher(cipherText);
        entity.setTemperature(new BigDecimal("0.70"));
        entity.setMaxTokens(2048);
        entity.setTimeoutMs(60000);
        entity.setIsEnabled(1);
        entity.setIsDefault(1);
        entity.setConfigVersion(1L);
        entity.setGmtModified(LocalDateTime.now());
        return entity;
    }

    /** 视觉用途的行：provider 走 OpenAI 兼容协议、用途=VISION（V45 起类型是显式列）。 */
    private static AiModelConfigEntity visionRow(String modelCode) {
        return visionRow(modelCode, null);
    }

    private static AiModelConfigEntity visionRow(String modelCode, String cipherText) {
        AiModelConfigEntity entity = row(modelCode, 2, cipherText);
        entity.setModelType(AiModelType.VISION.name());
        return entity;
    }

    /** 取消默认标记：用于构造"多行启用但没人标默认"这种最容易走偏的状态。 */
    private static AiModelConfigEntity noDefault(AiModelConfigEntity entity) {
        entity.setIsDefault(0);
        return entity;
    }

    private static AiModelSpec spec(String code, String endpoint, String key, String source) {
        return new AiModelSpec(code, code, 1, "https://api.deepseek.com", key, endpoint,
                0.7, null, 2048, null, Duration.ofSeconds(60), source, AiModelType.TEXT);
    }

    private static AiModelSpec visionSpec(String code, String endpoint, String key, String source) {
        return new AiModelSpec(code, code, 2, "https://dashscope.aliyuncs.com/compatible-mode/v1", key, endpoint,
                0.2, null, 512, null, Duration.ofSeconds(60), source, AiModelType.VISION);
    }

    private static AiRuntimeConfigService.Snapshot snapshotOf(AiModelSpec... specs) {
        Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
        Map<String, AiModelSpec> byName = new HashMap<>();
        AiModelSpec yamlText = null;
        AiModelSpec yamlVision = null;
        // 两遍：先放 DB 侧（覆盖语义），再放 yaml 侧（putIfAbsent）——与 AiRuntimeConfigService.load()
        // 完全一致。否则同 model_code 的两条规格会被 yaml 覆盖，测出来的就不是真实优先级。
        for (AiModelSpec spec : specs) {
            if (!"yaml".equals(spec.source())) {
                byCode.put(spec.modelCode(), spec);
                byName.put(spec.modelEndpoint(), spec);
            }
        }
        for (AiModelSpec spec : specs) {
            if ("yaml".equals(spec.source())) {
                byCode.putIfAbsent(spec.modelCode(), spec);
                byName.putIfAbsent(spec.modelEndpoint(), spec);
                if (spec.vision() && yamlVision == null) {
                    yamlVision = spec;
                } else if (!spec.vision() && yamlText == null) {
                    yamlText = spec;
                }
            }
        }
        if (yamlVision == null) {
            yamlVision = visionSpec("qwen3-vl-plus", "qwen3-vl-plus", "sk-yaml-vision", "yaml");
        }
        // 文本默认取第一份 DB 文本规格（等价于 load() 里"默认标记优先"的判定），没有就用 yaml
        AiModelSpec textDefault = byCode.values().stream()
                .filter(spec -> "db".equals(spec.source()))
                .filter(spec -> !spec.vision())
                .findFirst()
                .orElse(yamlText);
        AiModelSpec visionDefault = byCode.values().stream()
                .filter(spec -> "db".equals(spec.source()))
                .filter(AiModelSpec::vision)
                .findFirst()
                .orElse(null);
        return new AiRuntimeConfigService.Snapshot(1L, byCode, byName, yamlText, yamlVision, textDefault,
                "默认标记", visionDefault, visionDefault == null ? "yaml 兜底" : "默认标记", 0);
    }

    /** 只为构造"带 model 的 options"用的小工具，避免测试里散落构造细节。 */
    private static final class ChatOptionsHolder {
        org.springframework.ai.chat.prompt.ChatOptions options(String model) {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model);
            return builder.build();
        }
    }

    /** 让编译器保留未使用告警之外的引用（Generation/AssistantMessage/Flux 供后续断言扩展用）。 */
    @SuppressWarnings("unused")
    private static ChatResponse dummy() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    }

    @SuppressWarnings("unused")
    private static Flux<ChatResponse> dummyFlux() {
        return Flux.empty();
    }

    @SuppressWarnings("unused")
    private static List<String> unusedList() {
        return new ArrayList<>();
    }
}
