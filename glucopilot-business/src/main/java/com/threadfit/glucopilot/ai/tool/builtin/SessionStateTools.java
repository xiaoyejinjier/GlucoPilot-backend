package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.common.util.JsonUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话自身的状态查询工具（{@code get_session_state}）。
 *
 * <p><b>它存在的唯一理由是"别再重复查一遍"</b>：会话窗口只保留最近若干条消息，
 * 早几轮查过的血糖/饮食结论可能已经滑出窗口；没有这个工具，模型要么重复调用工具
 * （花掉一次模型往返与一次数据库查询），要么干脆忘了自己查过什么而给出自相矛盾的回答。
 * 这里把「本会话已经调用过哪些工具、各自结论的摘要」和「滚动摘要」一并交给模型。
 *
 * <p>刻意<b>不返回</b>完整消息列表：那等于把窗口又搬一遍，既浪费 token 也没新信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStateTools {

    /** 最多看最近多少次工具调用。 */
    private static final int TOOL_CALL_LIMIT = 8;

    /** 滚动摘要截断长度：摘要本身已经是压缩过的，再长就不是"状态"了。 */
    private static final int SUMMARY_MAX_CHARS = 600;

    /** 单条工具摘要的截断长度。 */
    private static final int DIGEST_MAX_CHARS = 160;

    private final ChatSessionService chatSessionService;

    private final ChatToolCallMapper chatToolCallMapper;

    private final JsonUtils jsonUtils;

    @Tool(
            name = "get_session_state",
            description =
                    "查询当前这次对话的状态：会话标题与进度、已生成的滚动摘要、本次对话已经调用过哪些工具及其结论摘要。"
                            + "当你不确定「刚才是不是查过这个」「这一轮之前聊到哪儿」，或准备重复调用某个查询工具之前，先调它。"
                            + "无参数。它不会返回聊天原文，也不会新增任何数据。")
    public String getSessionState(ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);
        Long sessionId = AiToolContext.optionalLong(toolContext, AiToolContext.SESSION_ID);
        if (sessionId == null) {
            return error("当前对话信息不可用（缺少 sessionId），无法读取会话状态");
        }

        ChatSession session;
        try {
            session = chatSessionService.requireOwned(userId, sessionId);
        } catch (RuntimeException e) {
            log.error("工具 get_session_state 读取会话失败 userId={} sessionId={}", userId, sessionId, e);
            return error("读取当前对话状态时出错，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> sessionMap = new LinkedHashMap<>();
        sessionMap.put("id", String.valueOf(session.getId()));
        sessionMap.put("title", session.getTitle());
        sessionMap.put("status", session.getSessionStatus());
        sessionMap.put("statusText", statusText(session.getSessionStatus()));
        sessionMap.put("msgCount", session.getMsgCount());
        sessionMap.put(
                "lastMsgAt",
                session.getLastMsgAt() == null ? null : session.getLastMsgAt().toString());
        result.put("session", sessionMap);

        String summary = session.getContextSummary();
        if (summary == null || summary.isBlank()) {
            result.put("summary", null);
            result.put("summaryNote", "本次对话还没有滚动摘要（消息数还少，历史消息本身就在上下文窗口里）");
        } else {
            Map<String, Object> summaryMap = new LinkedHashMap<>();
            summaryMap.put("text", truncate(summary, SUMMARY_MAX_CHARS));
            summaryMap.put("uptoMsgId", session.getSummaryUptoMsgId());
            result.put("summary", summaryMap);
        }

        List<ChatToolCall> calls;
        try {
            calls = chatToolCallMapper.selectRecentBySession(sessionId, TOOL_CALL_LIMIT);
        } catch (RuntimeException e) {
            log.warn("工具 get_session_state 读取工具调用记录失败 sessionId={}", sessionId, e);
            calls = List.of();
        }

        Set<String> names = new LinkedHashSet<>();
        List<Map<String, Object>> callItems = new ArrayList<>();
        if (calls != null) {
            for (ChatToolCall call : calls) {
                names.add(call.getToolName());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tool", call.getToolName());
                item.put("ok", call.getOk() == null || call.getOk() == 1);
                item.put(
                        "at", call.getGmtCreate() == null ? null : call.getGmtCreate().toString());
                item.put("digest", truncate(call.getResultDigest(), DIGEST_MAX_CHARS));
                callItems.add(item);
            }
        }
        result.put("calledTools", new ArrayList<>(names));
        result.put("toolCalls", callItems);
        result.put(
                "hint",
                callItems.isEmpty()
                        ? "本次对话还没调用过工具。"
                        : "上面这些工具本次对话已经调用过：结论与摘要可直接复用，不要重复调用；"
                                + "只有确实需要新的时间范围或新对象时才再调用一次。");

        log.info(
                "工具 get_session_state 执行 userId={} sessionId={} 工具调用 {} 次 有摘要={}",
                userId, sessionId, callItems.size(), summary != null && !summary.isBlank());
        return jsonUtils.toJson(result);
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "已归档";
            case 1 -> "进行中";
            case 2 -> "已结束";
            default -> "未知";
        };
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
