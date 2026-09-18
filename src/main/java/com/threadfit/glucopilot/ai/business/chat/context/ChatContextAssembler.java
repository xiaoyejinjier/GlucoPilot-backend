package com.threadfit.glucopilot.ai.business.chat.context;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 上下文装配：显式取「最近 N 条原文」拼成消息列表，不用框架的 ChatMemory。
 * <p>
 * 理由：助手消息要等流结束、拿到完整正文与 token 统计之后才落库，用内存版 ChatMemory
 * 重启即丢且多实例不一致，自定义 Repository 也绕不开同样的时序问题。
 * L2 换成 MessageChatMemoryAdvisor 时，本类可整体替换，调用方不变。
 */
@Component
@RequiredArgsConstructor
public class ChatContextAssembler {

    /** 会话未设 window_msg_count 时的兜底条数 */
    private static final int DEFAULT_WINDOW = 20;

    private final ChatMessageMapper chatMessageMapper;

    /**
     * 组装送入模型的历史消息（正序，已剔除空内容）。
     *
     * @param session 会话（提供窗口条数）
     * @param target  本轮要回复的用户消息，作为窗口上界
     */
    public List<Message> assemble(ChatSession session, ChatMessage target) {
        int limit = session.getWindowMsgCount() == null || session.getWindowMsgCount() < 1
                ? DEFAULT_WINDOW
                : session.getWindowMsgCount();

        List<ChatMessage> recent = chatMessageMapper.selectRecent(session.getId(), target.getSeqNo(), limit);
        List<Message> messages = new ArrayList<>(recent.size());
        // 查询是倒序，这里反转成正序，保证对话顺序正确
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage row = recent.get(i);
            String text = row.getContentText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (row.getMsgRole() != null && row.getMsgRole() == CodeEnum.ChatMsgRole.USER.getCode()) {
                messages.add(new UserMessage(text));
            } else if (row.getMsgRole() != null && row.getMsgRole() == CodeEnum.ChatMsgRole.ASSISTANT.getCode()) {
                messages.add(new AssistantMessage(text));
            }
        }
        return messages;
    }
}
