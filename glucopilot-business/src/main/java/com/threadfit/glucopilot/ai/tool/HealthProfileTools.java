package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康档案查询工具。
 * <p>
 * <b>⚠️ 本工具最关键的实现细节是"少返回一个字段"：刻意不回传 {@code medication}（用药情况）。</b>
 * 理由不是"模型看了没用"，而是<b>主动把风险信息送到模型嘴边</b>：
 * 一旦模型知道用户正在服用什么药，就极可能在回答里顺着聊用药、甚至给出剂量倾向的建议——
 * 而本项目的医疗红线明确"不给用药建议、不调整剂量、不给停药建议"。
 * 裁掉这个字段，是从信息源头减少越界的机会，而不是指望提示词每次都拦住。
 * <p>
 * 同类应裁掉的还有 {@code id}（内部主键，模型用不上）。
 * <p>
 * <b>为什么不返回目标值以外的字段</b>：年龄、糖尿病类型、确诊时间、运动习惯、血糖目标值
 * 这几项足以支撑"个性化建议"，且都属于用户自己填过、可合理引用的信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthProfileTools {

    private final HealthProfileService healthProfileService;
    private final JsonUtils jsonUtils;

    @Tool(name = "get_health_profile",
            description = "查询当前用户的健康档案：年龄、糖尿病类型、确诊时间、运动习惯，以及个性化的血糖控制目标值。"
                    + "用户询问「我的目标是多少」「结合我的情况给建议」「我多大/什么类型」时使用。"
                    + "注意：档案里若有用药信息，本工具不会返回，也不要向用户追问具体用药。")
    public String getHealthProfile(ToolContext toolContext) {
        Long userId = AiToolContext.requireUserId(toolContext);

        HealthView view;
        try {
            view = healthProfileService.healthViewOf(userId);
        } catch (RuntimeException e) {
            // 与血糖工具同一原则：数据源异常不把整轮对话打成 500
            log.error("工具 get_health_profile 查询失败 userId={}", userId, e);
            return error("读取健康档案时出错，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        boolean hasProfile = view != null && !isBlank(view);
        // 两条路径都打这一行：早先只在"有档案"分支打日志，导致"档案为空"这条最需要观察的路径
        // 在日志里完全不可见（端到端验证时被误判成"模型没调工具"）。观测点必须覆盖所有出口。
        log.info("工具 get_health_profile 执行 userId={} 有档案={}", userId, hasProfile);
        if (!hasProfile) {
            // 明确告知"档案为空"，让模型去引导用户补填，而不是凭空假设年龄/类型
            result.put("hasProfile", false);
            result.put("hint", "用户尚未填写健康档案，无法据此个性化；可提示用户去「健康档案」补充年龄、类型与目标值。");
            return jsonUtils.toJson(result);
        }

        result.put("hasProfile", true);
        result.put("age", view.getAge());
        result.put("diabetesType", view.getDiabetesTypeText());
        result.put("diagnosedYm", view.getDiagnosedYm());
        result.put("exerciseHabit", view.getExerciseHabit());
        result.put("targetFasting", view.getTargetFasting());
        result.put("targetPostprandial", view.getTargetPostprandial());
        // ⚠️ 刻意不 put("medication", ...) —— 见类注释
        return jsonUtils.toJson(result);
    }

    /** 档案是否"什么都没填"：只判断本工具会返回的那几个字段。 */
    private static boolean isBlank(HealthView view) {
        return view.getAge() == null
                && view.getDiabetesTypeText() == null
                && view.getDiagnosedYm() == null
                && view.getExerciseHabit() == null
                && view.getTargetFasting() == null
                && view.getTargetPostprandial() == null;
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
