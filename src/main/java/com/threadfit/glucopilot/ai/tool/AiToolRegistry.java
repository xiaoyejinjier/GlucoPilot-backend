package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具（Function Calling）白名单注册点。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>只注册显式登记的工具</b>：工具来源是标注了 {@code @Tool} 并通过 Spring 注册的
 *       {@link ToolCallback} Bean，再经 {@code ai.tools.allow-list} 名字白名单过滤。
 *       绝不做包扫描——「提示词注入诱导模型调用未授权工具」是已知风险，白名单是最后一道闸。</li>
 *   <li><b>调用次数上限交给框架</b>：{@code spring.ai.tools.limits.max-total-tool-calls} /
 *       {@code max-calls-per-tool} / {@code excluded-tools} / {@code on-limit-exceeded}（默认 throw），
 *       不要自己写计数器。</li>
 *   <li><b>越权防护在工具实现里</b>：用户身份必须由服务端通过 {@code toolContext} 注入，
 *       <b>不能</b>作为模型可见的参数，否则模型可被诱导去查他人数据。</li>
 * </ul>
 * 当前 {@code ai.tools.allow-list} 为空：领域模块（血糖/饮食/档案）尚未实现，没有可查的数据源，
 * 因此一个工具都不注册。等数据源就绪后，新增 {@code @Tool} 组件并在白名单里登记即可，
 * 本类无需修改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolRegistry {

    private final AIProperties aiProperties;

    /** 所有显式注册的工具 Bean（Spring 容器里实现 ToolCallback 的组件）。 */
    private final ObjectProvider<ToolCallback> registeredTools;

    /** 过滤后允许送给模型的工具清单。 */
    public List<ToolCallback> callbacks() {
        List<String> allowList = aiProperties.getTools().getAllowList();
        if (allowList == null || allowList.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> registered = registeredTools.stream().toList();
        List<ToolCallback> allowed = registered.stream()
                .filter(callback -> allowList.contains(callback.getToolDefinition().name()))
                .toList();

        Set<String> allowedNames = allowed.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> registeredNames = registered.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        warnAboutMissingBeans(allowList, registeredNames);
        log.info("AI 工具白名单生效：{}", allowedNames.isEmpty() ? "（无，模型不会收到任何工具）" : allowedNames);
        return allowed;
    }

    /** 白名单里配了名字但容器里没有对应 Bean：多半是名字拼错或组件没被扫描到，必须显式告警。 */
    private static void warnAboutMissingBeans(List<String> allowList, Set<String> registeredNames) {
        for (String name : allowList) {
            if (!registeredNames.contains(name)) {
                log.warn("ai.tools.allow-list 中的工具未找到对应 Bean：{}（名字是否与 ToolDefinition.name() 一致？）", name);
            }
        }
    }
}
