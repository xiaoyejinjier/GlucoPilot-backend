package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.client.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.support.lock.AiConcurrencyTestSupport;
import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V71「分类 + 多条目」的写入与注入语义。
 *
 * <p>这组用例守的是槽位模型被推翻之后最容易回退的两点：
 * <ol>
 *   <li><b>同一类可以有多条</b>——身份是"这句话"（{@code item_hash}），不是"这一类"。
 *       一旦有人把查询条件改回「只按 user + key 查」，第二条忌口就会覆盖掉第一条，
 *       而这类回退在功能上表现为"用户说过的话被吃掉"，很难在手工测试里发现；</li>
 *   <li><b>注入按类合并成一行</b>——多条目不能变成多行，否则五类事实会把历史消息的预算挤没。</li>
 * </ol>
 */
class UserChatMemoryItemTest {

    private ChatUserMemoryMapper memoryMapper;
    private AIProperties aiProperties;
    private UserChatMemoryServiceImpl service;

    private static final long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        var clients = mock(AiChatClientRegistry.class);
        memoryMapper = mock(ChatUserMemoryMapper.class);
        aiProperties = new AIProperties();
        service = new UserChatMemoryServiceImpl(clients, memoryMapper, mock(ChatMessageMapper.class),
                mock(ChatSessionMapper.class), mock(AiPromptTemplateService.class), mock(SceneModelResolver.class),
                aiProperties, new JsonUtils(new tools.jackson.databind.json.JsonMapper()), AiConcurrencyTestSupport.policy(),
                Runnable::run, org.mockito.Mockito.mock(com.threadfit.glucopilot.ai.memory.mq.MemoryExtractJobs.class));
    }

    @Test
    @DisplayName("同一类下不同内容会各成一个条目，而不是互相覆盖")
    void differentTextInSameKeyCreatesNewItem() {
        when(memoryMapper.selectOne(any())).thenReturn(null);

        assertThat(service.saveExplicit(USER_ID, 3L, "diet_taboo", "不吃香菜").saved()).isTrue();
        assertThat(service.saveExplicit(USER_ID, 3L, "diet_taboo", "海鲜过敏").saved()).isTrue();

        ArgumentCaptor<ChatUserMemoryEntity> captor = ArgumentCaptor.forClass(ChatUserMemoryEntity.class);
        verify(memoryMapper, times(2)).insert(captor.capture());
        List<ChatUserMemoryEntity> inserted = captor.getAllValues();
        assertThat(inserted).allSatisfy(row -> assertThat(row.getMemoryKey()).isEqualTo("diet_taboo"));
        assertThat(inserted.get(0).getItemHash()).isNotEqualTo(inserted.get(1).getItemHash());
        assertThat(inserted.get(0).getMemoryText()).isEqualTo("不吃香菜");
        assertThat(inserted.get(1).getMemoryText()).isEqualTo("海鲜过敏");
        // V93：写入来源必须落库，否则审计分不清「用户说的」与「模型猜的」
        assertThat(inserted).allSatisfy(row -> assertThat(row.getWriteSource()).isEqualTo("EXPLICIT"));
    }

    @Test
    @DisplayName("批量抽取写入时来源记为 BATCH，提升置信度的更新同样落来源")
    void batchWritesItsSourceOnBothInsertAndUpdate() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        service.write(USER_ID, "habit", "晚饭后散步30分钟", 70, 4L,
                UserChatMemoryService.WriteSource.BATCH);
        ArgumentCaptor<ChatUserMemoryEntity> insertCaptor = ArgumentCaptor.forClass(ChatUserMemoryEntity.class);
        verify(memoryMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getWriteSource()).isEqualTo("BATCH");

        ChatUserMemoryEntity existing = entity("habit", "晚饭后散步30分钟", 60);
        when(memoryMapper.selectOne(any())).thenReturn(existing);
        service.write(USER_ID, "habit", "晚饭后散步30分钟", 90, 5L,
                UserChatMemoryService.WriteSource.EXPLICIT);
        ArgumentCaptor<ChatUserMemoryEntity> updateCaptor = ArgumentCaptor.forClass(ChatUserMemoryEntity.class);
        verify(memoryMapper).updateById(updateCaptor.capture());
        // 覆盖写要记「现在这条是谁写的」，而不是「最初是谁写的」
        assertThat(updateCaptor.getValue().getWriteSource()).isEqualTo("EXPLICIT");
        assertThat(updateCaptor.getValue().getConfidence()).isEqualTo(90);
    }

    @Test
    @DisplayName("同一句话重复说（含空白差异）只算同一个条目，不再新增")
    void sameTextIsTheSameItem() {
        String text = "不吃香菜";
        ChatUserMemoryEntity existing = new ChatUserMemoryEntity();
        existing.setId(11L);
        existing.setUserId(USER_ID);
        existing.setMemoryKey("diet_taboo");
        existing.setMemoryText(text);
        existing.setItemHash(UserChatMemoryServiceImpl.itemHashOf(text));
        existing.setConfidence(UserChatMemoryServiceImpl.EXPLICIT_CONFIDENCE);
        when(memoryMapper.selectOne(any())).thenReturn(existing);

        // 多打了几个空格，规范化之后仍是同一句
        assertThat(service.saveExplicit(USER_ID, 3L, "diet_taboo", " 不吃香菜 ").saved()).isTrue();

        verify(memoryMapper, never()).insert(any(ChatUserMemoryEntity.class));
        // 置信度没变、来源没变 → 连 update 都不发（否则每次会话结束都会刷新"最近更新"）
        verify(memoryMapper, never()).updateById(any(ChatUserMemoryEntity.class));
    }

    @Test
    @DisplayName("批量抽取的置信度更低时不刷新已有条目，但仍算成功命中同一条")
    void batchLowerConfidenceDoesNotOverwrite() {
        String text = "晚饭后散步30分钟";
        ChatUserMemoryEntity existing = new ChatUserMemoryEntity();
        existing.setId(12L);
        existing.setUserId(USER_ID);
        existing.setMemoryKey("habit");
        existing.setMemoryText(text);
        existing.setItemHash(UserChatMemoryServiceImpl.itemHashOf(text));
        existing.setConfidence(95);
        when(memoryMapper.selectOne(any())).thenReturn(existing);

        UserChatMemoryService.SaveOutcome outcome = service.write(USER_ID, "habit", text, 70, 4L,
                UserChatMemoryService.WriteSource.BATCH);

        assertThat(outcome.saved()).isFalse();
        assertThat(outcome.reason()).contains("更可信");
        verify(memoryMapper, never()).updateById(any(ChatUserMemoryEntity.class));
    }

    @Test
    @DisplayName("注入时每类内逐条保留置信度、低置信度条目被过滤、类顺序固定")
    void injectionKeepsPerItemConfidenceAndFixedKeyOrder() {
        when(memoryMapper.selectTopPerKey(USER_ID, aiProperties.getMemory().getInjectMaxItemsPerKey()))
                .thenReturn(List.of(
                        // 库里按置信度降序返回：同类多条都留着（不再拼成一行）
                        entity("diet_taboo", "海鲜过敏", 95),
                        entity("preference", "喜欢早上测血糖", 88),
                        entity("diet_taboo", "不吃香菜", 90),
                        // 低置信度的推断不该注入：模型会把每一条都当作"用户说过的话"复述
                        entity("glucose_goal", "目标似乎是 7 以下", 40),
                        entity("habit", "晚饭后散步30分钟", 70)));

        List<UserChatMemoryService.MemoryItem> items = service.forInjection(USER_ID);

        // 类顺序跟 ALLOWED_KEY_ORDER（忌口 → 目标 → 状况 → 习惯 → 偏好），不是 DB 返回顺序；
        // glucose_goal 因为唯一一条低于 60 被整类过滤
        assertThat(items).extracting(UserChatMemoryService.MemoryItem::key)
                .containsExactly("diet_taboo", "habit", "preference");
        assertThat(items.get(0).entries()).extracting(UserChatMemoryService.MemoryItem.Entry::text)
                .containsExactly("海鲜过敏", "不吃香菜");
        assertThat(items.get(0).entries()).extracting(UserChatMemoryService.MemoryItem.Entry::confidence)
                .containsExactly(95, 90);
        assertThat(items.get(0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("注入时每类条数受 inject-max-items-per-key 约束")
    void injectionRespectsPerKeyLimit() {
        when(memoryMapper.selectTopPerKey(USER_ID, aiProperties.getMemory().getInjectMaxItemsPerKey()))
                .thenReturn(List.of(entity("diet_taboo", "不吃香菜", 95)));

        List<UserChatMemoryService.MemoryItem> items = service.forInjection(USER_ID);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).count()).isEqualTo(1);
        // 限量交给 SQL 的 ROW_NUMBER 窗口，不该在内存里再截一次（截断会让"另有 N 条"失真）
        verify(memoryMapper, never()).selectActiveByUser(any(), anyInt());
    }

    private static ChatUserMemoryEntity entity(String key, String text, int confidence) {
        ChatUserMemoryEntity row = new ChatUserMemoryEntity();
        row.setUserId(USER_ID);
        row.setMemoryKey(key);
        row.setMemoryText(text);
        row.setItemHash(UserChatMemoryServiceImpl.itemHashOf(text));
        row.setConfidence(confidence);
        row.setGmtModified(LocalDateTime.now());
        return row;
    }
}
