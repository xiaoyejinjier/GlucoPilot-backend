package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.common.util.JsonUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 跨会话的历史检索工具（{@code search_chat_history}）。
 *
 * <p><b>解决的是"窗口之外"的回忆</b>：上下文窗口只保留最近若干条消息，用户问
 * 「我上次说晚上几点吃的」「你之前建议过什么」时，那条内容早就不在窗口里了。
 * 没有这个工具，模型只能回答"记不清"，而这在产品上等同于助手失忆。
 *
 * <p><b>三条刻意的限制</b>：
 *
 * <ol>
 *   <li><b>只回片段，不回整段对话</b>：一段消息动辄几百字，回 5 段就能把一轮上下文挤满；
 *       片段的用途是让模型认出"是不是在说这件事"，真要全文再让用户去历史对话里看。
 *   <li><b>只搜本人、只搜未删除</b>：用户身份来自 {@link ToolContext}，SQL 里同时过滤
 *       {@code chat_message} 与 {@code chat_session} 的逻辑删除标记。
 *   <li><b>强制关键词、强制时间范围</b>：不给关键词就等于"把整段历史塞给模型"；
 *       默认 90 天是为了让"上次"落在合理区间里。
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryTools {

    /** 关键词长度上下限：太短会命中一切，太长说明模型把整句话当关键词。 */
    private static final int KEYWORD_MIN = 2;

    private static final int KEYWORD_MAX = 20;

    /** 默认回溯天数。 */
    private static final int DEFAULT_DAYS = 90;

    private static final int MAX_DAYS = 365;

    /** 默认/最多返回的会话条数。 */
    private static final int DEFAULT_LIMIT = 5;

    private static final int MAX_LIMIT = 10;

    /** 片段前后各留多少字。 */
    private static final int SNIPPET_PAD = 24;

    /** 一次多取几条再按会话去重：同一会话连发多条命中时，不能让它们挤掉别的会话。 */
    private static final int FETCH_FACTOR = 4;

    private final ChatMessageMapper chatMessageMapper;

    private final ChatSessionMapper chatSessionMapper;

    private final JsonUtils jsonUtils;

    @Tool(
            name = "search_chat_history",
            description =
                    "在当前用户的历史对话里按关键词检索，返回命中的会话标题、时间与关键词前后的一小段原文。"
                            + "用户提到「我上次说」「我之前问过」「你以前建议过」「还记得我说的…吗」这类窗口外的内容时使用。"
                            + "keyword 用用户话里最有辨识度的 2~20 个字（如「失眠」「二甲双胍」「早餐」），不要传整句话；"
                            + "days 默认 90，用户说「很久以前」可以给 365。")
    public String searchChatHistory(
            @ToolParam(description = "要检索的关键词，2~20 个字，取用户话里最有辨识度的名词或短语") String keyword,
            @ToolParam(required = false, description = "回溯多少天（含今天），默认 90，最长 365") Integer days,
            @ToolParam(required = false, description = "最多返回几个会话，默认 5，最多 10") Integer limit,
            ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);

        String word = keyword == null ? "" : keyword.trim();
        if (word.length() < KEYWORD_MIN) {
            return error("keyword 至少 " + KEYWORD_MIN + " 个字：请取用户话里最有辨识度的词，而不是「上次」这类泛指");
        }
        if (word.length() > KEYWORD_MAX) {
            return error("keyword 最多 " + KEYWORD_MAX + " 个字：请只取最有辨识度的词，不要传整句话");
        }

        int span = days == null ? DEFAULT_DAYS : days;
        if (span < 1 || span > MAX_DAYS) {
            return error("days 需在 1~" + MAX_DAYS + " 之间");
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            return error("limit 需在 1~" + MAX_LIMIT + " 之间");
        }

        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(span - 1L);
        LocalDateTime from = fromDate.atStartOfDay();

        List<ChatMessage> rows;
        try {
            rows = chatMessageMapper.searchInUserSessions(userId, from, word, size * FETCH_FACTOR);
        } catch (RuntimeException e) {
            log.error("工具 search_chat_history 查询失败 userId={} keyword={}", userId, word, e);
            return error("检索历史对话时出错，请稍后重试");
        }

        // 按会话去重：同一会话的多条命中留最新一条，把名额让给别的会话
        Map<Long, ChatMessage> bySession = new LinkedHashMap<>();
        Set<Long> sessionIds = new LinkedHashSet<>();
        for (ChatMessage row : rows) {
            if (row.getSessionId() == null) {
                continue;
            }
            if (bySession.putIfAbsent(row.getSessionId(), row) == null) {
                sessionIds.add(row.getSessionId());
            }
            if (bySession.size() >= size) {
                break;
            }
        }

        Map<Long, String> titles = titles(sessionIds);

        List<Map<String, Object>> items = new ArrayList<>(bySession.size());
        for (ChatMessage row : bySession.values()) {
            String snippet = snippet(row, word);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", String.valueOf(row.getSessionId()));
            item.put("sessionTitle", titles.get(row.getSessionId()));
            item.put("role", row.getMsgRole());
            item.put("roleName", roleName(row.getMsgRole()));
            item.put("at", row.getGmtCreate() == null ? null : row.getGmtCreate().toString());
            item.put("snippet", snippet == null ? "" : snippet);
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", word);
        result.put("from", fromDate.toString());
        result.put("to", today.toString());
        result.put("sessionCount", items.size());
        result.put("items", items);
        result.put("hint", items.isEmpty()
                ? "最近 " + span + " 天没有检索到相关内容。可以换一个更口语的关键词再试一次，仍没有就如实告诉用户「没找到」，不要编造他以前说过的话。"
                : "这里只有片段。引用时要说清是哪次对话（时间/标题），不要把它当成用户此刻的原话；需要完整上下文请看那个会话。");
        log.info(
                "工具 search_chat_history 执行 userId={} keyword={} 命中会话 {} 个",
                userId, word, items.size());
        return jsonUtils.toJson(result);
    }

    private Map<Long, String> titles(Set<Long> sessionIds) {
        Map<Long, String> titles = new LinkedHashMap<>();
        if (sessionIds.isEmpty()) {
            return titles;
        }
        try {
            List<ChatSession> sessions = chatSessionMapper.selectBatchIds(sessionIds);
            if (sessions != null) {
                for (ChatSession session : sessions) {
                    titles.put(session.getId(), session.getTitle());
                }
            }
        } catch (RuntimeException e) {
            // 标题只是装饰：查不到就用空标题，不能让"能检索到内容"这件事跟着失败
            log.warn("检索历史对话时读取会话标题失败，本次只返回片段 {}", sessionIds, e);
        }
        return titles;
    }

    /** 取命中位置前后一小段；图片描述、语音转写同样参与（用户说的「上次那张图」就在里面）。 */
    private static String snippet(ChatMessage row, String keyword) {
        String[] candidates = {row.getContentText(), row.getAsrText(), row.getMediaCaption()};
        for (String text : candidates) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String lowerText = text.toLowerCase(Locale.ROOT);
            int index = lowerText.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (index < 0) {
                continue;
            }
            int start = Math.max(0, index - SNIPPET_PAD);
            int end = Math.min(text.length(), index + keyword.length() + SNIPPET_PAD);
            String slice = text.substring(start, end).replaceAll("\\s+", " ").trim();
            return (start > 0 ? "…" : "") + slice + (end < text.length() ? "…" : "");
        }
        return null;
    }

    private static String roleName(Integer role) {
        if (role == null) {
            return "未知";
        }
        return switch (role) {
            case 1 -> "用户";
            case 2 -> "助手";
            default -> "系统";
        };
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
