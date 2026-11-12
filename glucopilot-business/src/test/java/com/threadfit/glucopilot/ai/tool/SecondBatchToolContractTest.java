package com.threadfit.glucopilot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;
import com.threadfit.glucopilot.ai.tool.builtin.ArticleSearchTools;
import com.threadfit.glucopilot.ai.tool.builtin.ChatHistoryTools;
import com.threadfit.glucopilot.ai.tool.builtin.GlucoseStatsTools;
import com.threadfit.glucopilot.ai.tool.builtin.ReminderTools;
import com.threadfit.glucopilot.ai.tool.builtin.SessionStateTools;
import com.threadfit.glucopilot.ai.tool.port.ArticleSearchQuery;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.service.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.service.GlucoseRecordService;
import com.threadfit.glucopilot.notification.dto.UserReminderDtos;
import com.threadfit.glucopilot.notification.service.UserReminderService;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第二批工具（{@code query_glucose_stats} / {@code search_chat_history} / {@code get_session_state} /
 * {@code list_reminders} / {@code search_community_articles}）的契约。
 *
 * <p>共同的三条：<b>越界参数给出可读错误而不是抛异常</b>、<b>空数据不编造数字</b>、
 * <b>返回体积有上限</b>（模型上下文是有限的，工具不能把明细整张搬进去）。
 */
class SecondBatchToolContractTest {

    private static final Long USER_ID = 7L;

    private static final Long SESSION_ID = 19L;

    private static final ToolContext CONTEXT =
            new ToolContext(Map.of(AiToolContext.USER_ID, USER_ID, AiToolContext.SESSION_ID, SESSION_ID));

    private static JsonUtils json() {
        return new JsonUtils(new ObjectMapper());
    }

    private static GlucoseNode node(String code, String name, int meal) {
        GlucoseNode node = new GlucoseNode();
        node.setNodeCode(code);
        node.setNodeName(name);
        node.setIsMeal(meal);
        return node;
    }

    private static GlucoseRecord glucose(String nodeCode, String value, LocalDate date) {
        GlucoseRecord record = new GlucoseRecord();
        record.setNodeCode(nodeCode);
        record.setGlucoseValue(new BigDecimal(value));
        record.setRecordDate(date);
        return record;
    }

    // ---------- query_glucose_stats ----------

    @Test
    void 血糖统计应给出达标率并把偏高偏低分开计() {
        GlucoseRecordService recordService = mock(GlucoseRecordService.class);
        GlucosePeriodService periodService = mock(GlucosePeriodService.class);
        HealthProfileService profileService = mock(HealthProfileService.class);

        LocalDate today = LocalDate.now();
        List<GlucoseRecord> rows =
                List.of(
                        glucose("before_breakfast", "6.0", today),
                        glucose("after_lunch", "9.0", today.minusDays(1)),
                        glucose("before_lunch", "3.5", today.minusDays(2)));
        when(recordService.listRange(eq(USER_ID), any(), any())).thenReturn(rows);
        when(periodService.nodeMap())
                .thenReturn(
                        Map.of(
                                "before_breakfast", node("before_breakfast", "早餐前", 0),
                                "after_lunch", node("after_lunch", "午餐后", 1),
                                "before_lunch", node("before_lunch", "午餐前", 0)));

        String json =
                new GlucoseStatsTools(recordService, periodService, profileService, json())
                        .queryGlucoseStats(30, CONTEXT);

        assertThat(json)
                .contains("\"recordCount\":3")
                .contains("\"count\":1") // 只有 6.0 落在 3.9~6.1 之间
                .contains("\"lowCount\"")
                .contains("\"source\":\"default\"") // 档案没填目标值，必须标注兜底
                .doesNotContain("items");
        // 明细不外泄：工具只给聚合，逐条明细留给 query_glucose_range
        assertThat(json).doesNotContain("\"6.0\"");
    }

    @Test
    void 血糖统计的天数越界应给可读错误() {
        String json =
                new GlucoseStatsTools(
                                mock(GlucoseRecordService.class),
                                mock(GlucosePeriodService.class),
                                mock(HealthProfileService.class),
                                json())
                        .queryGlucoseStats(200, CONTEXT);

        assertThat(json).contains("error").contains("92");
    }

    // ---------- search_chat_history ----------

    @Test
    void 历史检索应按会话去重并只给关键词片段() {
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);

        ChatMessage first = new ChatMessage();
        first.setId(100L);
        first.setSessionId(SESSION_ID);
        first.setMsgRole(1);
        first.setContentText("今天早餐后血糖 9.8，有点担心是不是吃多了面条");
        first.setGmtCreate(LocalDateTime.now());

        ChatMessage second = new ChatMessage();
        second.setId(101L);
        second.setSessionId(SESSION_ID);
        second.setMsgRole(2);
        second.setContentText("血糖偏高可以先看看最近几天的趋势");
        second.setGmtCreate(LocalDateTime.now());

        ChatMessage other = new ChatMessage();
        other.setId(102L);
        other.setSessionId(SESSION_ID + 1);
        other.setMsgRole(1);
        other.setContentText("血糖又是 9.8");
        other.setGmtCreate(LocalDateTime.now());

        when(messageMapper.searchInUserSessions(eq(USER_ID), any(), eq("血糖"), anyInt()))
                .thenReturn(List.of(first, second, other));

        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        session.setTitle("餐后血糖偏高");
        when(sessionMapper.selectBatchIds(any())).thenReturn(List.of(session));

        String json =
                new ChatHistoryTools(messageMapper, sessionMapper, json())
                        .searchChatHistory("血糖", 90, 5, CONTEXT);

        assertThat(json)
                .contains("\"sessionCount\":2")
                .contains("餐后血糖偏高")
                .contains("9.8")
                .contains("snippet");
        // 原文不外泄：只给片段，不给整段对话
        assertThat(json).doesNotContain("contentText");
    }

    @Test
    void 历史检索的关键词过短应给可读错误() {
        String json =
                new ChatHistoryTools(
                                mock(ChatMessageMapper.class), mock(ChatSessionMapper.class), json())
                        .searchChatHistory("血", 90, 5, CONTEXT);

        assertThat(json).contains("error").contains("2");
    }

    // ---------- get_session_state ----------

    @Test
    void 会话状态应回摘要与已调用的工具清单() {
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ChatToolCallMapper toolCallMapper = mock(ChatToolCallMapper.class);

        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        session.setTitle("早餐后血糖");
        session.setSessionStatus(1);
        session.setMsgCount(8);
        session.setContextSummary("用户主诉早餐后血糖偏高，已建议记录餐后 2 小时血糖。");
        session.setSummaryUptoMsgId(6L);
        when(sessionService.requireOwned(USER_ID, SESSION_ID)).thenReturn(session);

        ChatToolCall call = new ChatToolCall();
        call.setToolName("query_glucose_range");
        call.setOk(1);
        call.setResultDigest("avg=8.9,count=6");
        call.setGmtCreate(LocalDateTime.now());
        when(toolCallMapper.selectRecentBySession(eq(SESSION_ID), anyInt())).thenReturn(List.of(call));

        String json =
                new SessionStateTools(sessionService, toolCallMapper, json())
                        .getSessionState(CONTEXT);

        assertThat(json)
                .contains("\"statusText\":\"进行中\"")
                .contains("早餐后血糖偏高")
                .contains("query_glucose_range")
                .contains("avg=8.9")
                .contains("不要重复调用");
        assertThat(json).doesNotContain("contentText");
    }

    @Test
    void 缺会话标识时会话状态应给可读错误() {
        String json =
                new SessionStateTools(
                                mock(ChatSessionService.class),
                                mock(ChatToolCallMapper.class),
                                json())
                        .getSessionState(new ToolContext(Map.of(AiToolContext.USER_ID, USER_ID)));

        assertThat(json).contains("error").contains("sessionId");
    }

    // ---------- list_reminders ----------

    @Test
    void 今日提醒应只列前几条并给出总数() {
        UserReminderService reminderService = mock(UserReminderService.class);
        List<UserReminderDtos.Item> alerts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            alerts.add(
                    new UserReminderDtos.Item(
                            "GLUCOSE",
                            "id-" + i,
                            "rev-" + i,
                            "rec-" + i,
                            LocalDate.now(),
                            "slot-" + i,
                            "时段" + i,
                            null,
                            null,
                            null,
                            null,
                            false));
        }
        when(reminderService.list(eq(USER_ID), any(LocalDate.class), any()))
                .thenReturn(
                        new UserReminderDtos.Result(
                                LocalDate.now(), alerts, PageResult.empty(1, 3)));

        String json = new ReminderTools(reminderService, json()).listReminders(false, CONTEXT);

        assertThat(json)
                .contains("\"alertCount\":9")
                .contains("\"nodeName\":\"时段0\"")
                .contains("\"nodeName\":\"时段5\"")
                .doesNotContain("\"nodeName\":\"时段6\"") // 上限 6 条
                .contains("不能写入");
    }

    @Test
    void 提醒查询失败应返回错误而不是抛异常() {
        UserReminderService reminderService = mock(UserReminderService.class);
        when(reminderService.list(anyLong(), any(LocalDate.class), any()))
                .thenThrow(new IllegalStateException("db down"));

        String json = new ReminderTools(reminderService, json()).listReminders(false, CONTEXT);

        assertThat(json).contains("error");
    }

    // ---------- search_community_articles ----------

    @Test
    void 社区检索应只给摘要与作者并说明来源() {
        ArticleSearchQuery query = mock(ArticleSearchQuery.class);
        when(query.search(USER_ID, "餐后血糖", 3))
                .thenReturn(
                        List.of(
                                new ArticleSearchQuery.Hit(
                                        "12",
                                        "餐后血糖总超标，我是这样调整的",
                                        "连续两周餐后 9 以上，记录饮食后发现是主食量的问题。",
                                        "小糖",
                                        List.of("经验分享"),
                                        "2026-10-01T09:00:00",
                                        8,
                                        3)));

        String json =
                new ArticleSearchTools(query, json()).searchArticles("餐后血糖", null, CONTEXT);

        assertThat(json)
                .contains("餐后血糖总超标")
                .contains("小糖")
                .contains("\"itemCount\":1")
                .contains("来自社区");
        // 正文不给：让用户自己点进去
        assertThat(json).doesNotContain("body");
    }

    @Test
    void 社区检索没命中时应如实说明而不是编造() {
        ArticleSearchQuery query = mock(ArticleSearchQuery.class);
        when(query.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        String json = new ArticleSearchTools(query, json()).searchArticles("生酮饮食", 2, CONTEXT);

        assertThat(json).contains("\"itemCount\":0").contains("没有检索到");
    }

    @Test
    void 社区检索的关键词越界应给可读错误() {
        String json =
                new ArticleSearchTools(mock(ArticleSearchQuery.class), json())
                        .searchArticles("x", 3, CONTEXT);

        assertThat(json).contains("error").contains("2");
    }
}
