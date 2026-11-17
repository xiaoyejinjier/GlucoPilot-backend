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
 *
 * <p><b>去重粒度是「一问一答」而不是「会话」</b>：早先版本每个会话只留最新一条命中，
 * 结果是「上次说二甲双胍时我还问了什么」这类**天然集中在一个会话里**的追问被裁成一句，
 * 而 {@code hint} 里"去那个会话看"却没有可执行路径。现在改成：同一轮的用户问 + 助手答只留一条
 * （避免同一句话被算两次），不同轮各自保留，每个会话最多 {@value #MAX_ROUNDS_PER_SESSION} 处；
 * 需要更多时把返回里的 {@code sessionId} 传回来续查。
 *
 * <p><b>取数是游标翻页而不是固定倍数</b>：关键词可能密集出现在少数几个会话里，
 * 固定 {@code limit * 4} 的窗口会"只取回一个会话的命中、凑不够要返回的会话数"。
 * 现在取不满就按 {@code id} 往前翻，直到够 {@value #MAX_PAGES} 页或触顶 {@value #MAX_FETCH} 条。
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

    /** 首屏取回 = limit × 这个倍数；取不满再翻页。 */
    private static final int FETCH_FACTOR = 4;

    /** 单次检索最多取回多少条原始命中（翻页的总兜底）。 */
    private static final int MAX_FETCH = 200;

    /** 最多翻几页：极端数据（同一会话刷屏）下不让一次工具调用无限放大查询。 */
    private static final int MAX_PAGES = 4;

    /** 跨会话模式下，每个会话最多给几处命中（不同轮）。 */
    private static final int MAX_ROUNDS_PER_SESSION = 2;

    private final ChatMessageMapper chatMessageMapper;

    private final ChatSessionMapper chatSessionMapper;

    private final JsonUtils jsonUtils;

    @Tool(
            name = "search_chat_history",
            description =
                    "在当前用户的历史对话里按关键词检索，返回命中的会话标题、时间与关键词前后的一小段原文。"
                            + "同一轮问答只算一条，每个会话最多给 2 处命中，所以 limit 指的是「几个会话」。"
                            + "用户提到「我上次说」「我之前问过」「你以前建议过」「还记得我说的…吗」这类窗口外的内容时使用。"
                            + "keyword 用用户话里最有辨识度的 2~20 个字（如「失眠」「二甲双胍」「早餐」），不要传整句话；"
                            + "days 默认 90，用户说「很久以前」可以给 365。"
                            + "如果返回里提示某个会话还有更多命中，把那个 sessionId 再传进来，就只在该会话里继续检索。")
    public String searchChatHistory(
            @ToolParam(description = "要检索的关键词，2~20 个字，取用户话里最有辨识度的名词或短语") String keyword,
            @ToolParam(required = false, description = "回溯多少天（含今天），默认 90，最长 365") Integer days,
            @ToolParam(required = false, description = "最多返回几个会话，默认 5，最多 10")
            Integer limit,
            @ToolParam(
                            required = false,
                            description = "只查这一个会话内的命中（用上一轮返回里的 sessionId）；此时 limit 表示最多返回几处")
                    Long sessionId,
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
            rows = sessionId == null
                    ? fetchAcrossSessions(userId, from, word, size)
                    : fetchOneSession(userId, sessionId, from, word, size);
        } catch (RuntimeException e) {
            log.error("工具 search_chat_history 查询失败 userId={} keyword={} sessionId={}",
                    userId, word, sessionId, e);
            return error("检索历史对话时出错，请稍后重试");
        }

        // 会话内模式：limit 就是片段数；跨会话模式：每个会话 ≤2 处，总量随 limit 走
        int sessionCap = sessionId != null ? size : MAX_ROUNDS_PER_SESSION;
        int snippetCap = sessionId != null ? size : Math.min(size * MAX_ROUNDS_PER_SESSION, MAX_FETCH);

        Map<Long, Group> groups = new LinkedHashMap<>();
        for (ChatMessage row : rows) {
            if (row.getSessionId() == null) {
                continue;
            }
            Group group = groups.computeIfAbsent(row.getSessionId(), key -> new Group());
            // 同一轮问答只保留一条（用户问 + 助手答的内容高度重合，算两次纯属浪费名额）
            if (!group.rounds.add(roundKey(row))) {
                continue;
            }
            if (group.picked.size() < sessionCap) {
                group.picked.add(row);
            }
        }

        List<ChatMessage> flat = new ArrayList<>(Math.min(groups.size() * sessionCap, snippetCap));
        for (Group group : groups.values()) {
            for (ChatMessage row : group.picked) {
                if (flat.size() >= snippetCap) {
                    break;
                }
                flat.add(row);
            }
            if (flat.size() >= snippetCap) {
                break;
            }
        }

        Set<Long> sessionIds = new LinkedHashSet<>();
        for (ChatMessage row : flat) {
            sessionIds.add(row.getSessionId());
        }
        Map<Long, String> titles = titles(sessionIds);

        List<Map<String, Object>> items = new ArrayList<>(flat.size());
        Set<Long> returnedSessions = new LinkedHashSet<>();
        Long firstWithMore = null;
        for (ChatMessage row : flat) {
            Group group = groups.get(row.getSessionId());
            int rounds = group == null ? 1 : group.rounds.size();
            boolean moreInSession = group != null && rounds > group.picked.size();
            if (moreInSession && firstWithMore == null && sessionId == null) {
                firstWithMore = row.getSessionId();
            }
            String snippet = snippet(row, word);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", String.valueOf(row.getSessionId()));
            item.put("sessionTitle", titles.get(row.getSessionId()));
            item.put("role", row.getMsgRole());
            item.put("roleName", roleName(row.getMsgRole()));
            item.put("at", row.getGmtCreate() == null ? null : row.getGmtCreate().toString());
            item.put("snippet", snippet == null ? "" : snippet);
            item.put("sessionMatches", rounds);
            item.put("moreInSession", moreInSession);
            items.add(item);
            returnedSessions.add(row.getSessionId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", word);
        result.put("from", fromDate.toString());
        result.put("to", today.toString());
        if (sessionId != null) {
            result.put("sessionId", String.valueOf(sessionId));
        }
        result.put("sessionCount", returnedSessions.size());
        result.put("snippetCount", items.size());
        result.put("scanned", rows.size());
        result.put("items", items);
        result.put("hint", hint(sessionId, items, firstWithMore, span, rows.size()));
        log.info("工具 search_chat_history 执行 userId={} keyword={} sessionId={} 扫描 {} 条、返回 {} 个会话 / {} 段",
                userId, word, sessionId, rows.size(), returnedSessions.size(), items.size());
        return jsonUtils.toJson(result);
    }

    /**
     * 跨会话取数：先取 {@code size * 4} 条，凑不够 {@code size} 个会话就按 id 往前翻。
     *
     * <p>固定倍数在「关键词密集出现在少数会话」时会失效（20 条全来自 1 个会话），
     * 翻页才是真正的兜底；{@link #MAX_PAGES} 与 {@link #MAX_FETCH} 保证放大有界。
     */
    private List<ChatMessage> fetchAcrossSessions(Long userId, LocalDateTime from, String word, int size) {
        List<ChatMessage> rows = new ArrayList<>();
        Long beforeId = null;
        int fetch = Math.min(size * FETCH_FACTOR, MAX_FETCH);
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ChatMessage> batch = chatMessageMapper.searchInUserSessions(
                    userId, null, from, word, beforeId, fetch);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            rows.addAll(batch);
            boolean windowFull = batch.size() >= fetch;
            boolean enoughSessions = distinctSessions(rows) >= size;
            if (!windowFull || enoughSessions || rows.size() >= MAX_FETCH) {
                break;
            }
            ChatMessage last = batch.get(batch.size() - 1);
            beforeId = last.getId();
            fetch = Math.min(Math.max(fetch, size) * 2, MAX_FETCH - rows.size());
            if (fetch <= 0) {
                break;
            }
        }
        return rows;
    }

    /** 会话内取数：一次取回即可（limit ≤ 10，取回窗口足够覆盖）。 */
    private List<ChatMessage> fetchOneSession(
            Long userId, Long sessionId, LocalDateTime from, String word, int size) {
        int fetch = Math.min(Math.max(size * FETCH_FACTOR, size), MAX_FETCH);
        List<ChatMessage> rows =
                chatMessageMapper.searchInUserSessions(userId, sessionId, from, word, null, fetch);
        return rows == null ? List.of() : rows;
    }

    private static int distinctSessions(List<ChatMessage> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ChatMessage row : rows) {
            if (row.getSessionId() != null) {
                ids.add(row.getSessionId());
            }
        }
        return ids.size();
    }

    /**
     * 一问答为一组：用户与助手成对写入，所以 {@code seq_no} 相邻两条同属一轮
     * （{@code (seq + 1) / 2} 与 {@code chat_context} 的轮次口径一致）。
     * 没有 seq_no 时退化成按消息 id 分组——至少不会把两条不同的命中误判成同一轮。
     */
    private static String roundKey(ChatMessage row) {
        Integer seq = row.getSeqNo();
        if (seq != null && seq > 0) {
            return "r" + ((seq + 1) / 2);
        }
        return "m" + row.getId();
    }

    private String hint(Long sessionId, List<Map<String, Object>> items, Long firstWithMore,
                        int span, int scanned) {
        if (items.isEmpty()) {
            if (sessionId != null) {
                return "这个会话里最近 " + span + " 天没有检索到「该关键词」。可以换一个更口语的词再试，"
                        + "或去掉 sessionId 在其他会话里找一次；仍没有就如实告诉用户「没找到」。";
            }
            return "最近 " + span + " 天没有检索到相关内容。可以换一个更口语的关键词再试一次，仍没有就如实告诉用户「没找到」，不要编造他以前说过的话。";
        }
        StringBuilder text = new StringBuilder();
        text.append("这里只有片段：引用时要说清是哪次对话（时间/标题），不要把它当成用户此刻的原话。");
        if (firstWithMore != null) {
            text.append("会话 ").append(firstWithMore)
                    .append(" 里还有更多命中，需要的话带上 sessionId=").append(firstWithMore)
                    .append(" 再查一次。");
        }
        if (scanned >= MAX_FETCH) {
            text.append("命中很多，把 days 收窄会更准。");
        }
        return text.toString();
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

    /** 一个会话的命中：{@code rounds} 是命中的轮次数，{@code picked} 是实际回给模型的那几处。 */
    private static final class Group {

        private final Set<String> rounds = new LinkedHashSet<>();

        private final List<ChatMessage> picked = new ArrayList<>();
    }
}
