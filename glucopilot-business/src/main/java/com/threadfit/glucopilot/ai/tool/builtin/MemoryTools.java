package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.common.util.JsonUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 长期记忆的<b>唯一写工具</b>（{@code save_long_term_memory}）。
 *
 * <h2>为什么"记住"要做成工具，而不是继续用关键词判定</h2>
 * 旧实现是在用户消息里找「记住 / 记一下 / 帮我记」这类字眼，命中就异步抽一次记忆。这条规则有天然缺陷：
 * 用户会说「我最近不能吃辣了」「医生让我少油少盐」「我女儿也查出糖耐量异常」——一个"记住"都没有，
 * 但都是该记的长期事实；反过来「记住这个单号」又不是记忆。判断"这句话是否陈述了一条长期事实"
 * 本质上是语义问题，交给模型比继续堆关键词更准，而且模型还能顺手把键（五类）和文本规范化好。
 *
 * <h2>为什么与"会话结束抽取"并存</h2>
 * 单轮视角看不到逐渐暴露的事实：第 3 轮「下午容易饿」、第 8 轮「饿的时候手抖」、第 12 轮
 * 「医生怀疑餐后低血糖」——任何单独一轮都不足以判定"有餐后低血糖风险"，只有把十几轮放一起复盘
 * 才抽得出来。所以工具负责"当场记"，会话结束的批量抽取负责"复盘补漏"，两者不是替代关系。
 *
 * <h2>这个工具打破了"工具全只读"的纪律，为什么可以</h2>
 * 它是<b>可撤销</b>（用户可在记忆页删）、<b>有界</b>（5 键 × 每键 1 条 × 200 字）、
 * 且<b>不触碰任何业务单据状态</b>（不改血糖、饮食、档案）。与"让模型自行写健康数据"不是一类风险。
 * 若将来要加"写血糖/写饮食"这类工具，那必须走用户显式确认，不能照抄这里的做法。
 *
 * <h2>返回给模型的东西</h2>
 * 极短回执（{@code {"ok":true}} 或 {@code {"ok":false,"reason":"…"}}）：工具回执会进上下文，
 * 而这里真正需要模型知道的只有"记住没有、为什么没记住"。拒绝原因是可执行的具体说明，
 * 让它知道该换键还是该放弃（例如涉及用药时长记忆一律不写，重试也没用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryTools {

    private final UserChatMemoryService memories;
    private final JsonUtils jsonUtils;

    /**
     * 记录一条用户的长期事实。
     *
     * <p>只记<b>长期不变</b>的个人事实：忌口、控糖目标、健康状况、习惯、偏好。
     * 一次性事件（"今天吃了碗面"）、临时状态（"现在有点头晕"）、以及任何诊断/用药细节都不要记——
     * 诊断与用药在服务端还会被二次拦截（含「剂量/停药/处方」等词直接拒写）。
     */
    @Tool(name = "save_long_term_memory",
            description = "把用户的长期事实记入跨会话记忆（下一轮对话就会生效）。"
                    + "仅当用户明确要求记住，或主动陈述长期不变的个人事实时调用："
                    + "忌口/过敏、控糖目标、健康状况、习惯、偏好。"
                    + "不要记一次性事件（今天吃了什么）、临时状态（此刻的感受）、诊断结论或用药细节。"
                    + "同一个键再次写入会覆盖旧值，用户更正时直接再调一次即可。")
    public String saveLongTermMemory(
            @ToolParam(description = "记忆键，只能是以下五个之一："
                    + "diet_taboo（忌口/过敏）、glucose_goal（控糖目标，如空腹<6.1）、"
                    + "health_context（长期健康状况）、habit（作息/运动/饮食等习惯）、"
                    + "preference（沟通与生活偏好）") String key,
            @ToolParam(description = "用第三人称复述用户的事实，一句话、不超过 200 字，"
                    + "例如「用户不吃香菜」「用户希望空腹血糖控制在 6.1 以下」") String text,
            ToolContext toolContext) {
        Long userId;
        try {
            userId = AiToolContext.requireUserId(toolContext);
        } catch (IllegalStateException e) {
            // 代码缺陷（调用方没注入 userId），不是用户输入问题：记日志 + 给模型一句"没记成"
            log.warn("保存长期记忆缺少用户上下文", e);
            return error("缺少用户上下文，未记录");
        }
        Long sessionId = AiToolContext.optionalLong(toolContext, AiToolContext.SESSION_ID);

        UserChatMemoryService.SaveOutcome outcome = memories.saveExplicit(userId, sessionId, key, text);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", outcome.saved());
        body.put("key", key);
        if (outcome.saved()) {
            body.put("message", outcome.reason());
        } else {
            body.put("reason", outcome.reason());
        }
        return jsonUtils.toJson(body);
    }

    private String error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return jsonUtils.toJson(body);
    }
}
