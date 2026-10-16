package com.threadfit.glucopilot.ai.business.chat.controller;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatMessageService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ChatHistoryControllerTest {
    private final ChatMessageService messages = mock(ChatMessageService.class);
    private final ChatController controller = new ChatController(mock(ChatSessionService.class), messages,
            mock(ChatCompletionService.class));

    @Test
    void getHistoryUsesAuthenticatedUserAndSerializesPagingContract() throws Exception {
        var page = ChatDtos.HistoryResponse.builder().records(List.of(ChatDtos.HistoryMessageResponse.builder()
                .id(18L).seqNo(18).msgRole(2).msgType(1).contentText("小语之前的回复").build()))
                .hasMore(true).nextBeforeSeqNo(18).build();
        when(messages.history(2L, 9L, 20, 10)).thenReturn(page);
        when(messages.history(2L, 9L, null, 50)).thenReturn(page);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        RequestContext.setUserId(2L);
        try {
            var response = mvc.perform(get("/api/v1/chat-sessions/9/messages")
                    .param("beforeSeqNo", "20").param("pageSize", "10")).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            var json = new ObjectMapper().readTree(response.getContentAsByteArray());
            assertEquals(0, json.get("code").asInt());
            assertTrue(json.get("data").get("hasMore").asBoolean());
            assertEquals(18, json.get("data").get("nextBeforeSeqNo").asInt());
            assertEquals("小语之前的回复", json.get("data").get("records").get(0).get("contentText").asText());
            assertEquals(200, mvc.perform(get("/api/v1/chat-sessions/9/messages")).andReturn().getResponse().getStatus());
            verify(messages).history(2L, 9L, null, 50);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void missingAuthenticationCannotReachHistoryService() {
        RequestContext.clear();
        assertThrows(BizException.class, () -> controller.history(9L, null, 50));
        verifyNoInteractions(messages);
    }
}
