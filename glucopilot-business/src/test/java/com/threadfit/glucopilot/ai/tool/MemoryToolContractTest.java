package com.threadfit.glucopilot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.tool.builtin.MemoryTools;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.ai.tool.runtime.ToolRepeatGuard;
import com.threadfit.glucopilot.common.util.JsonUtils;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * {@code save_long_term_memory}（唯一写工具）的契约。
 *
 * <p>三条必须守住的：
 * <b>写成功要回极短回执</b>（工具回执会进上下文，不该把记忆正文再回显一遍）；
 * <b>拒绝要给出可执行原因</b>（模型据此换键或放弃，重试同样内容没有意义）；
 * <b>缺用户上下文不能抛异常</b>（工具抛异常会让整轮对话失败，而这只该记一条日志）。
 */
class MemoryToolContractTest {

    private static final Long USER_ID = 7L;

    private static final Long SESSION_ID = 19L;

    private static JsonUtils json() {
        return new JsonUtils(new ObjectMapper());
    }

    private static ToolContext context(Long userId, Long sessionId) {
        Map<String, Object> values = sessionId == null
                ? Map.of(AiToolContext.USER_ID, userId)
                : Map.of(AiToolContext.USER_ID, userId, AiToolContext.SESSION_ID, sessionId);
        return new ToolContext(values);
    }

    @Test
    void 写成功时只回短回执且带上键名() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.saveExplicit(USER_ID, SESSION_ID, "diet_taboo", "用户不吃香菜"))
                .thenReturn(UserChatMemoryService.SaveOutcome.ok());

        String json = new MemoryTools(memories, json(), new ToolRepeatGuard()).saveLongTermMemory(
                "diet_taboo", "用户不吃香菜", context(USER_ID, SESSION_ID));

        assertThat(json).contains("\"ok\":true").contains("diet_taboo");
        // 回执里不该重复记忆正文：正文已经写进库里，回显只会白占上下文
        assertThat(json).doesNotContain("用户不吃香菜");
    }

    @Test
    void 被拒绝时把可执行原因回给模型() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.saveExplicit(any(), any(), any(), any()))
                .thenReturn(UserChatMemoryService.SaveOutcome.rejected("内容涉及诊断或用药细节，未记录"));

        String json = new MemoryTools(memories, json(), new ToolRepeatGuard()).saveLongTermMemory(
                "health_context", "用户每天打 12 单位胰岛素", context(USER_ID, SESSION_ID));

        assertThat(json).contains("\"ok\":false").contains("用药");
    }

    @Test
    void 缺少用户上下文时返回error而不是抛异常() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);

        String json = new MemoryTools(memories, json(), new ToolRepeatGuard()).saveLongTermMemory(
                "habit", "用户通常晚上十一点睡", new ToolContext(Map.of()));

        assertThat(json).contains("error");
        verify(memories, org.mockito.Mockito.never()).saveExplicit(any(), any(), any(), any());
    }

    @Test
    void 没有会话id时照样写入只是不带来源会话() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.saveExplicit(eq(USER_ID), eq(null), eq("preference"), any()))
                .thenReturn(UserChatMemoryService.SaveOutcome.ok());

        String json = new MemoryTools(memories, json(), new ToolRepeatGuard()).saveLongTermMemory(
                "preference", "用户更喜欢简短回答", context(USER_ID, null));

        assertThat(json).contains("\"ok\":true");
        verify(memories).saveExplicit(USER_ID, null, "preference", "用户更喜欢简短回答");
    }
}
