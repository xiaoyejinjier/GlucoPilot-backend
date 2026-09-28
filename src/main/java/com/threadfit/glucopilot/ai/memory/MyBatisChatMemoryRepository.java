package com.threadfit.glucopilot.ai.memory;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆的存储实现：把 Spring AI 的 {@link ChatMemoryRepository} 落到 ``chat_message`` 表。
 * <p>
 * 为什么用框架的接口 + 自研实现：
 * <ul>
 *   <li>Spring AI 2.0.1 只随包提供 {@code InMemoryChatMemoryRepository}（重启即丢、多实例不一致），
 *       官方 JDBC 版不在本项目的依赖里，所以必须自己实现——好在只有 4 个方法；</li>
 *   <li>本项目的消息表比通用 JDBC 记忆表丰富（seq_no / token 计量 / finish_reason / 幂等键），
 *       通用实现装不下这些字段，自研反而更贴;</li>
 *   <li>框架的 {@code ChatMemory}（{@code MessageWindowChatMemory}）会自动使用本 Bean
 *       （其自动装配带 {@code @ConditionalOnMissingBean}），窗口裁剪语义直接复用框架实现。</li>
 * </ul>
 * <p>
 * <b>写入不在这里发生</b>：助手消息必须等流式结束、拿到完整正文与 token 计量后才由
 * {@code ChatCompletionService} 显式落库。因此 {@link #saveAll} 主动抛异常，
 * 目的是「一旦有人挂上会写记忆的 Advisor，立刻炸出来」，而不是悄悄写出半截数据。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MyBatisChatMemoryRepository implements ChatMemoryRepository {

    /** 会话 ID ↔ conversationId 的转换：框架只认字符串。 */
    private static final String UNSUPPORTED_SAVE =
            "会话记忆的写入由 ChatCompletionService 显式完成（需等流结束拿全正文与 token 计量）；"
                    + "请勿挂载会自动写记忆的 Advisor，否则会与落库时序冲突。";

    private final ChatMessageMapper chatMessageMapper;
    private final AIProperties aiProperties;

    /** 本项目不按会话枚举（无此需求，且会全表扫描），返回空列表。 */
    @Override
    public List<String> findConversationIds() {
        return List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Long sessionId = parseSessionId(conversationId);
        if (sessionId == null) {
            return List.of();
        }
        List<ChatMessage> rows = chatMessageMapper.selectRecentBySession(
                sessionId, aiProperties.getMemory().getMaxFetch());
        if (rows.isEmpty()) {
            return List.of();
        }
        // 查询是倒序（取最近 N 条），这里反转回时间正序，保证对话顺序正确
        List<Message> messages = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            Message message = toMessage(rows.get(i));
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        throw new UnsupportedOperationException(UNSUPPORTED_SAVE);
    }

    /**
     * 逻辑删除会话内全部消息（供接口 5.4 删除会话使用）。
     * DDL 的 {@code chk_cm_del_consistent} 只要求 is_deleted=1 时 deleted_at 非空，deleted_by 可空。
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        Long sessionId = parseSessionId(conversationId);
        if (sessionId == null) {
            return;
        }
        int deleted = chatMessageMapper.logicDeleteBySession(sessionId);
        log.info("会话记忆逻辑删除 sessionId={} rows={}", sessionId, deleted);
    }

    private static Long parseSessionId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(conversationId.trim());
        } catch (NumberFormatException e) {
            log.warn("非法的 conversationId（应为 chat_session.id）：{}", conversationId);
            return null;
        }
    }

    /** 只回放用户与助手的文本消息：系统提示词由 ChatClient 的 defaultSystem 提供，不参与历史回放。 */
    private static Message toMessage(ChatMessage row) {
        String text = row.getContentText();
        if (text == null || text.isBlank() || row.getMsgRole() == null) {
            return null;
        }
        int role = row.getMsgRole();
        if (role == CodeEnum.ChatMsgRole.USER.getCode()) {
            return new UserMessage(text);
        }
        if (role == CodeEnum.ChatMsgRole.ASSISTANT.getCode()) {
            return new AssistantMessage(text);
        }
        return null;
    }
}
