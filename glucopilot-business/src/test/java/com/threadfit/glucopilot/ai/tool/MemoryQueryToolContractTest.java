package com.threadfit.glucopilot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.tool.builtin.MemoryQueryTools;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.ai.tool.runtime.ToolRepeatGuard;
import com.threadfit.glucopilot.common.util.JsonUtils;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code list_long_term_memory}（读工具）的契约。
 *
 * <p>这个工具有四件事一旦做错就会静默伤到体验，所以逐条锁住：
 * <ol>
 *   <li><b>必须报告总数</b>：只给条目的话，模型会把"我看到 15 条"说成"你一共 15 条"；</li>
 *   <li><b>筛选要真的生效</b>：非法类不能静默当成"全部"，否则模型以为筛过了、复述的却是混着的清单；</li>
 *   <li><b>limit 越界只夹紧不报错</b>：报错等于白烧一轮，模型还得重试同一个调用；</li>
 *   <li><b>读失败不能伪装成空列表</b>：说"你没有记忆"比说"我查不到"更糟；</li>
 *   <li><b>不泄露内部字段</b>：主键、item_hash、置信度数值都不该出现在回执里。</li>
 * </ol>
 */
class MemoryQueryToolContractTest {

    private static final Long USER_ID = 7L;

    private static JsonUtils json() {
        return new JsonUtils(new ObjectMapper());
    }

    /** 缺 userId 的场景要用空 map：{@code Map.of} 不接受 null，会在造上下文时就 NPE，测不到被测代码。 */
    private static ToolContext context(Long userId) {
        Map<String, Object> values = new java.util.HashMap<>();
        if (userId != null) {
            values.put(AiToolContext.USER_ID, userId);
        }
        return new ToolContext(values);
    }

    private static UserChatMemoryService.MemoryOverview overview(
            int total, boolean truncated, UserChatMemoryService.MemoryEntry... items) {
        return new UserChatMemoryService.MemoryOverview(
                total,
                Map.of("diet_taboo", 2, "habit", 0, "glucose_goal", 0, "health_context", 0, "preference", 0),
                List.of(items),
                truncated);
    }

    private static UserChatMemoryService.MemoryEntry entry(String key, String text) {
        return new UserChatMemoryService.MemoryEntry(key, text, LocalDateTime.of(2026, 11, 17, 9, 30));
    }

    @Test
    void 回执要带总数与中文标签便于模型如实描述() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.overviewForModel(eq(USER_ID), isNull(), isNull(), anyInt()))
                .thenReturn(overview(2, false, entry("diet_taboo", "用户不吃香菜"), entry("diet_taboo", "用户海鲜过敏")));

        String json = new MemoryQueryTools(memories, json()).listLongTermMemory(null, null, null, context(USER_ID));

        assertThat(json)
                .contains("\"total\":2")
                .contains("\"shown\":2")
                .contains("\"truncated\":false")
                .contains("饮食忌口")
                .contains("用户海鲜过敏")
                .contains("2026-11-17 09:30");
    }

    @Test
    void 不泄露主键与置信度与内部哈希() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.overviewForModel(anyLong(), any(), any(), anyInt()))
                .thenReturn(overview(1, false, entry("habit", "用户每天晚饭后散步30分钟")));

        String json = new MemoryQueryTools(memories, json())
                .listLongTermMemory("habit", "散步", 5, context(USER_ID));

        assertThat(json).doesNotContain("itemHash").doesNotContain("item_hash")
                .doesNotContain("confidence").doesNotContain("sourceSessionId")
                .doesNotContain("\"id\"");
    }

    @Test
    void 按类与关键词透传给服务() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.overviewForModel(eq(USER_ID), eq("diet_taboo"), eq("海鲜"), eq(5)))
                .thenReturn(overview(1, false, entry("diet_taboo", "用户海鲜过敏")));

        new MemoryQueryTools(memories, json()).listLongTermMemory("diet_taboo", "海鲜", 5, context(USER_ID));

        verify(memories).overviewForModel(USER_ID, "diet_taboo", "海鲜", 5);
    }

    @Test
    void 非法类直接报错且不查库() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);

        String json = new MemoryQueryTools(memories, json())
                .listLongTermMemory("image", null, null, context(USER_ID));

        assertThat(json).contains("\"error\"").contains("image");
        verify(memories, never()).overviewForModel(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void 关键词超长直接报错而不是截断() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);

        String json = new MemoryQueryTools(memories, json())
                .listLongTermMemory(null, "一二三四五六七八九十一二三四五六七八九十一", null, context(USER_ID));

        assertThat(json).contains("\"error\"").contains("关键词太长");
        verify(memories, never()).overviewForModel(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void limit越界只夹紧不报错() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.overviewForModel(anyLong(), any(), any(), anyInt()))
                .thenReturn(overview(0, false));
        MemoryQueryTools tools = new MemoryQueryTools(memories, json());

        tools.listLongTermMemory(null, null, null, context(USER_ID));
        tools.listLongTermMemory(null, null, 99, context(USER_ID));
        tools.listLongTermMemory(null, null, 0, context(USER_ID));

        verify(memories).overviewForModel(USER_ID, null, null, 15);
        verify(memories).overviewForModel(USER_ID, null, null, 30);
        verify(memories).overviewForModel(USER_ID, null, null, 1);
    }

    @Test
    void 缺用户上下文返回错误而不是抛异常() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);

        String json = new MemoryQueryTools(memories, json()).listLongTermMemory(null, null, null, context(null));

        assertThat(json).contains("\"error\"");
        verify(memories, never()).overviewForModel(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void 读库异常返回错误而不是空列表() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.overviewForModel(anyLong(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        String json = new MemoryQueryTools(memories, json()).listLongTermMemory(null, null, null, context(USER_ID));

        assertThat(json).contains("\"error\"").doesNotContain("\"total\":0");
    }

    @Test
    void 还有更多时提示不要说成全部() {
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.overviewForModel(anyLong(), any(), any(), anyInt()))
                .thenReturn(overview(27, true, entry("diet_taboo", "用户不吃香菜")));

        String json = new MemoryQueryTools(memories, json()).listLongTermMemory(null, null, 1, context(USER_ID));

        assertThat(json).contains("\"truncated\":true").contains("共 27 条").contains("不要把 1 条说成全部");
    }

    @Test
    void 写成功要作废读工具的缓存() {
        // 同一轮里"先查、再写、再查"很常见：不作废的话第二次读会拿到写之前的空结果
        UserChatMemoryService memories = mock(UserChatMemoryService.class);
        when(memories.saveExplicit(USER_ID, 19L, "diet_taboo", "用户不吃香菜"))
                .thenReturn(UserChatMemoryService.SaveOutcome.ok());
        ToolRepeatGuard guard = new ToolRepeatGuard();
        guard.remember(new ToolRepeatGuard.Key(19L, MemoryQueryTools.TOOL_NAME, "{}"), "{\"total\":0}");

        new com.threadfit.glucopilot.ai.tool.builtin.MemoryTools(memories, json(), guard)
                .saveLongTermMemory("diet_taboo", "用户不吃香菜",
                        new ToolContext(Map.of(AiToolContext.USER_ID, USER_ID, AiToolContext.SESSION_ID, 19L)));

        assertThat(guard.recall(new ToolRepeatGuard.Key(19L, MemoryQueryTools.TOOL_NAME, "{}"))).as("写入后读工具缓存应被作废").isEmpty();
    }
}
