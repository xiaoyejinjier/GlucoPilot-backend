package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;
import com.threadfit.glucopilot.ai.model.runtime.ChatModelFactory;
import com.threadfit.glucopilot.ai.model.runtime.DynamicChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.function.Supplier;

/**
 * 把"可热替换的模型"接进 Spring AI 的装配链。
 *
 * <h2>为什么必须是 {@code @Primary} 的 {@code ChatModel}，而不是自己造 ChatClient</h2>
 * {@code ChatClientAutoConfiguration.chatClientBuilder(..., ChatModel chatModel, ...)} 是<b>单值注入</b>
 * （已用 {@code javap} 核对字节码），容器里出现多个 {@code ChatModel} 时由 {@code @Primary} 决定用哪个。
 * 于是：
 * <ul>
 *   <li>自动装配的 Builder 绑到本委托 → {@code AiChatClientConfig} 照旧 clone 它 →
 *       <b>工具（ToolCallingAdvisor）、Advisor 链、观测全部保持原样</b>；</li>
 *   <li>后台改配置只是换委托内部的引用，装配结构不动。</li>
 * </ul>
 * 反过来，如果为了"动态"而手搓 {@code ChatClient.builder(...)}，会静默丢掉全部工具
 * ——这是本项目已经记录过的坑（见 {@code AiChatClientConfig} 的注释）。
 *
 * <h2>与 starter 的关系（协议统一后已不存在）</h2>
 * 协议统一（2026-11）之后不再引入 DeepSeek starter，容器里<b>没有</b>第二个 ChatModel Bean，
 * 因此 {@code yamlFallback} 一直是 null，yaml 规格只能由 {@link ChatModelFactory} 现造。
 * 构造里保留这个参数，是为了「将来若有人再加一个 ChatModel Bean」时它的模型能被复用（而不是被顶掉）。
 */
@Slf4j
@Configuration
public class AiDynamicModelConfig {

    /**
     * 文本通道：{@code @Primary}，是自动装配 Builder 实际使用的那个 ChatModel。
     * <p>
     * yaml 兜底用 {@code ObjectProvider} <b>懒解析</b>：真实取用发生在第一次模型调用时。
     */
    @Bean
    @Primary
    // 与 AiChatClientConfig 同一条件：spring.ai.model.chat=none 表示「无 Key 部署」，文本 AI 整体关闭。
    @ConditionalOnExpression("'${spring.ai.model.chat:}' != 'none'")
    public ChatModel glucopilotTextChatModel(AiRuntimeConfigService runtimeConfig,
                                             ChatModelFactory chatModelFactory,
                                             ObjectProvider<ChatModel> allChatModels) {
        Supplier<ChatModel> yamlFallback = () -> allChatModels.stream()
                .filter(model -> !(model instanceof DynamicChatModel))
                .findFirst()
                .orElse(null);
        return new DynamicChatModel("text", runtimeConfig, chatModelFactory,
                // 通道默认 = 快照加载时定下的"文本默认规格"（默认标记 → 唯一启用 → 最近修改 → yaml）。
                // ⚠️ 不能写成 resolveText(null)：那样会直接返回 yaml 规格；
                // ⚠️ 也不能写成 resolveText(yamlText().modelCode())：那会永远盯着 yaml 里那个模型名，
                //    后台把它停用、改启用另一个模型后依然解析回 yaml 的旧模型（2026-10-13 的线上反馈）。
                AiRuntimeConfigService.Snapshot::resolveTextDefault,
                AiRuntimeConfigService.Snapshot::yamlText,
                yamlFallback);
    }

    /**
     * 视觉通道：不是 {@code @Primary}，由 {@code AiVisionClientConfig} 显式按名字注入。
     * <p>
     * 没有 yaml 兜底模型可复用（视觉 provider 是另一套 ChatModel），因此 yaml 规格也走工厂构造。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai.vision", name = "enabled", havingValue = "true")
    public ChatModel glucopilotVisionChatModel(AiRuntimeConfigService runtimeConfig,
                                               ChatModelFactory chatModelFactory) {
        return new DynamicChatModel("vision", runtimeConfig, chatModelFactory,
                AiRuntimeConfigService.Snapshot::resolveVisionDefault,
                AiRuntimeConfigService.Snapshot::yamlVision,
                null);
    }
}
