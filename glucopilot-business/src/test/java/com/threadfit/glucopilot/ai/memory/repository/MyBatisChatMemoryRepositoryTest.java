package com.threadfit.glucopilot.ai.memory.repository;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.memory.repository.MyBatisChatMemoryRepository;

/**
 * 图片消息进入会话记忆的回归测试（阶段 1 的摘要回放）。
 * <p>
 * 这组用例对应一个真实的坑：图片消息（msg_type=2）的 {@code content_text} 为空，
 * 而记忆装配原先<b>两处</b>都会把它丢掉——
 * 一处是 {@code ChatMessageMapper.selectRecentBySession} 的 SQL 过滤，
 * 另一处是 {@code toMessage} 的 Java 判空。
 * 只改其中一处的话，摘要写进了库却依然回放不出来，表现为
 * 「明明存了，模型就是不记得这张图」，非常难定位。
 * 因此这里同时锁住「摘要能被回放」「纯空消息仍被丢弃」「有原话时优先原话」三条。
 */
class MyBatisChatMemoryRepositoryTest {

    private static final long SESSION_ID = 9L;

    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final MyBatisChatMemoryRepository repository =
            new MyBatisChatMemoryRepository(chatMessageMapper, new AIProperties());

    private int maxFetch() {
        // 与实现同源：避免把 200 这个数字在测试里再抄一遍
        return new AIProperties().getMemory().getMaxFetch();
    }

    @Test
    void 图片消息应靠摘要进入会话记忆且带前缀标明是图片() {
        // 查询结果是倒序（最近的最前），与 mapper 的 ORDER BY seq_no DESC 一致
        when(chatMessageMapper.selectRecentBySession(SESSION_ID, maxFetch()))
                .thenReturn(List.of(image(1), text(CodeEnum.ChatMsgRole.USER.getCode(), "早餐前血糖 6.8 正常吗")));

        List<Message> messages = repository.findByConversationId(String.valueOf(SESSION_ID));

        assertEquals(2, messages.size(), "图片消息不该被丢掉");
        // 反转回时间正序：先用户文字，后图片摘要
        assertEquals("早餐前血糖 6.8 正常吗", messages.get(0).getText());
        assertEquals(MessageType.USER, messages.get(1).getMessageType());
        assertTrue(messages.get(1).getText().startsWith("[用户发来的图片，内容摘要]"),
                "必须标明这是图片摘要，否则模型会把它当成用户的原话：" + messages.get(1).getText());
        assertTrue(messages.get(1).getText().contains("一碗牛肉面"));
    }

    @Test
    void 有原话的图片消息应优先回放原话而不是摘要() {
        ChatMessage row = image(1);
        row.setContentText("这是我今天的早餐，碳水高吗？");
        when(chatMessageMapper.selectRecentBySession(SESSION_ID, maxFetch())).thenReturn(List.of(row));

        List<Message> messages = repository.findByConversationId(String.valueOf(SESSION_ID));

        assertEquals(1, messages.size());
        assertEquals("这是我今天的早餐，碳水高吗？", messages.get(0).getText(),
                "用户说了话就回放原话，摘要只在没有原话时兜底");
    }

    @Test
    void 原话与摘要都为空的消息仍应被丢弃() {
        when(chatMessageMapper.selectRecentBySession(SESSION_ID, maxFetch()))
                .thenReturn(List.of(text(CodeEnum.ChatMsgRole.USER.getCode(), "   "),
                        text(CodeEnum.ChatMsgRole.USER.getCode(), null)));

        assertTrue(repository.findByConversationId(String.valueOf(SESSION_ID)).isEmpty(),
                "两张兜底都不成立时，空消息不应进入记忆");
    }

    @Test
    void 非法的conversationId应返回空列表而不是报错() {
        assertTrue(repository.findByConversationId("not-a-number").isEmpty());
        assertTrue(repository.findByConversationId(null).isEmpty());
    }

    // ---------- 构造辅助 ----------

    private static ChatMessage image(int seqNo) {
        ChatMessage row = new ChatMessage();
        row.setSeqNo(seqNo);
        row.setMsgRole(CodeEnum.ChatMsgRole.USER.getCode());
        row.setMsgType(CodeEnum.ChatMsgType.IMAGE.getCode());
        row.setMediaCaption("一碗牛肉面，汤面，份量约一碗");
        return row;
    }

    private static ChatMessage text(Integer role, String contentText) {
        ChatMessage row = new ChatMessage();
        row.setMsgRole(role);
        row.setMsgType(CodeEnum.ChatMsgType.TEXT.getCode());
        row.setContentText(contentText);
        return row;
    }
}
