package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具（Function Calling）白名单注册点。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>只注册显式登记的工具</b>：工具来源有两条——{@link ToolCallbackProvider} Bean
 *       （{@code AiToolConfig} 里为每个工具类注册一个）与直接声明的 {@link ToolCallback} Bean；
 *       汇总后再经 {@code ai.tools.allow-list} 名字白名单过滤。
 *       绝不做包扫描——「提示词注入诱导模型调用未授权工具」是已知风险，白名单是最后一道闸。</li>
 *   <li><b>调用次数上限交给框架</b>：{@code spring.ai.tools.limits.max-total-tool-calls} /
 *       {@code max-calls-per-tool} / {@code excluded-tools} / {@code on-limit-exceeded}（默认 throw），
 *       不要自己写计数器。</li>
 *   <li><b>越权防护在工具实现里</b>：用户身份必须由服务端通过 {@code toolContext} 注入
 *       （见 {@link AiToolContext}），<b>不能</b>作为模型可见的参数，否则模型可被诱导去查他人数据。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolRegistry {

    private final AIProperties aiProperties;

    /** 工具类注册的提供者（每个工具类一个 Bean）。 */
    private final ObjectProvider<ToolCallbackProvider> toolProviders;

    /** 直接以单个 ToolCallback 形式注册的工具。 */
    private final ObjectProvider<ToolCallback> singleCallbacks;

    /** 工具调用元数据登记（B3）：给每个放行的工具套一层装饰器。 */
    private final ToolCallTraceRecorder traceRecorder;

    /** 过滤后允许送给模型的工具清单。 */
    public List<ToolCallback> callbacks() {
        List<ToolCallback> registered = registered();
        List<String> allowList = aiProperties.getTools().getAllowList();
        if (allowList == null || allowList.isEmpty()) {
            log.info("AI 工具白名单为空：一个工具都不会注册（模型不会收到任何工具）。"
                    + "容器里已就绪 {} 个：{}", registered.size(), names(registered));
            return List.of();
        }
        List<ToolCallback> allowed = registered.stream()
                .filter(callback -> allowList.contains(callback.getToolDefinition().name()))
                .toList();

        Set<String> allowedNames = names(allowed);
        warnAboutMissingBeans(allowList, names(registered));
        log.info("AI 工具白名单生效：{}", allowedNames.isEmpty() ? "（无，模型不会收到任何工具）" : allowedNames);
        // 统一套一层记账装饰器（B3）：模型能看到的每个工具都会被记下"调没调、成没成、多久"。
        // 包在这里而不是各调用点：只此一处，新增工具自动获得同样的可观测性。
        return allowed.stream()
                .map(callback -> (ToolCallback) new TracingToolCallback(callback, traceRecorder))
                .toList();
    }

    /** 汇总两条来源的工具，按名字去重（同名以先注册者为准，并告警）。 */
    private List<ToolCallback> registered() {
        List<ToolCallback> all = new ArrayList<>();
        toolProviders.stream()
                .flatMap(provider -> {
                    // provider 契约上是数组，但第三方/自定义实现返回 null 并不罕见；
                    // 这里若直接 Arrays.stream(null) 会在启动期 NPE，把一个"某个工具类没注册好"
                    // 放大成"整个应用起不来"。
                    ToolCallback[] callbacks = provider.getToolCallbacks();
                    return callbacks == null ? java.util.stream.Stream.empty() : Arrays.stream(callbacks);
                })
                .filter(java.util.Objects::nonNull)
                .forEach(all::add);
        singleCallbacks.stream().filter(java.util.Objects::nonNull).forEach(all::add);

        Set<String> seen = new LinkedHashSet<>();
        List<ToolCallback> unique = new ArrayList<>(all.size());
        for (ToolCallback callback : all) {
            String name = callback.getToolDefinition().name();
            if (seen.add(name)) {
                unique.add(callback);
            } else {
                // 静默丢一个同名工具，会让"模型看到的工具没按预期生效"变成无从排查的悬案
                log.warn("同名工具重复注册，已忽略后注册的那个：name={}（模型只会看到先注册的实现）", name);
            }
        }
        return List.copyOf(unique);
    }

    private static Set<String> names(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 白名单里配了名字但容器里没有对应 Bean：多半是名字拼错或组件没被扫描到，必须显式告警。 */
    private static void warnAboutMissingBeans(List<String> allowList, Set<String> registeredNames) {
        for (String name : allowList) {
            if (!registeredNames.contains(name)) {
                log.warn("ai.tools.allow-list 中的工具未找到对应 Bean：{}（名字是否与 @Tool(name=...) 一致？）", name);
            }
        }
    }
}
