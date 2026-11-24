package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话记忆相关配置的自洽性校验（启动期，只 WARN 不阻断）。
 *
 * <h2>为什么需要校验</h2>
 * 「窗口条数 / 保底条数 / 摘要目标水位」三项是互相咬合的：装配器给"摘要指针之后的未摘要消息"
 * 留了必留带，而必留带正常能长到 {@code keep-recent-messages + window × summary-target-ratio}
 * （摘要压到目标水位就停，之后仍会长回去）。若 {@code window-messages} 比这个大，必留带就被条数上限
 * 挤掉——表现出来就是模型"忘了刚才聊过什么"，而日志只会打一句「空洞=N」，很难联想到是配置少写了一位数。
 *
 * <p>因此这里在启动时把关系算一遍：{@code window ≥ keep + round(window × target-ratio) + 2}
 * （+2 给"摘要晚半拍"的抖动）。<b>只 WARN 不 throw</b>：配置可疑不该让整个服务起不来
 * （与项目其他配置校验的取舍一致），但日志里必须给出推荐值，让改配置的人一眼看到该改哪个数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemoryConfigCheck {

    /** 摘要晚半拍（异步任务还没落库、下一轮就到了）的抖动余量。 */
    static final int SHIFT_SLACK = 2;

    private final AIProperties aiProperties;

    @PostConstruct
    public void check() {
        AIProperties.MemoryProperties memory = aiProperties.getMemory();
        String violation = violation(memory);
        if (violation != null) {
            log.warn("会话记忆配置不自洽：{}", violation);
        }
    }

    /**
     * 检查配置并返回问题描述（自洽时返回 null）。
     *
     * <p>抽成静态方法是为了单测能直接喂各种组合，不用起 Spring 容器。
     */
    static String violation(AIProperties.MemoryProperties memory) {
        int window = memory.getWindowMessages();
        int keep = Math.max(memory.getKeepRecentMessages(), 0);
        // 摘要门改成"水位"模型后，真正的门槛是 target（window × summary-target-ratio）：
        // 只有压完能落到窗口装得下的位置，才不会出现"刚压完又立刻超窗"的空洞
        double ratio = memory.getSummaryTargetRatio();
        int target = Math.max(1, (int) Math.round(window * (ratio <= 0 || ratio >= 1 ? 0.5 : ratio)));
        if (keep >= window) {
            return "keep-recent-messages(" + keep + ") ≥ window-messages(" + window + ")："
                    + "保底条数已占满窗口，摘要指针之后的消息必然被丢（空洞）。"
                    + "建议 window-messages ≥ keep-recent-messages + window × summary-target-ratio";
        }
        int required = keep + target + SHIFT_SLACK;
        if (window < required) {
            return "window-messages(" + window + ") < keep-recent-messages(" + keep + ") + 目标水位("
                    + target + " = window × summary-target-ratio) + " + SHIFT_SLACK + " = " + required
                    + "：窗口装不下「未摘要的全部消息」，摘要稍慢一拍就会漏内容（日志里的「空洞」）。"
                    + "建议把 window-messages 调到 " + required + " 以上，或调小 summary-target-ratio。";
        }
        if (memory.getMaxPromptTokens() < 8000) {
            // 实测固定开销（CHAT 场景提示词 + 13 个工具定义）就有 5444~6700 token，
            // 预算比它小意味着装配循环里的预算判断永远为假，只有 keep-recent 生效
            return "max-prompt-tokens(" + memory.getMaxPromptTokens() + ") 小于固定开销的实测区间（5444~6700）："
                    + "预算判断将永远为假，真正生效的只有 keep-recent-messages。建议 ≥ 12000。";
        }
        return null;
    }
}
