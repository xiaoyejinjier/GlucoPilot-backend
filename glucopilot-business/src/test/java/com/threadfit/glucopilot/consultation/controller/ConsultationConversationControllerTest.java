package com.threadfit.glucopilot.consultation.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.consultation.service.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class ConsultationConversationControllerTest {
    @Test
    void imagesAndMessagesRequireCurrentLoginAndValidateReply() throws Exception {
        var service = mock(ConsultationService.class);
        var media = mock(ConsultationMediaService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConsultationConversationController(service, media))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        try {
            RequestContext.clear();
            assertEquals(
                    401,
                    mvc.perform(get("/api/v1/consultations/8/messages"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            RequestContext.setUserId(1L);
            when(service.images(1, 8)).thenReturn(List.of());
            var images =
                    mvc.perform(get("/api/v1/consultations/8/images")).andReturn().getResponse();
            assertEquals(200, images.getStatus());
            assertEquals("no-store", images.getHeader("Cache-Control"));
            assertEquals(
                    400,
                    mvc.perform(
                                    post("/api/v1/consultations/8/messages")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"content\":\""
                                                        + " \",\"clientRequestId\":\"valid_reply_key\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    200,
                    mvc.perform(
                                    post("/api/v1/consultations/8/messages")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"content\":\"补充情况\",\"clientRequestId\":\"valid_reply_key\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            verify(service)
                    .sendMessage(eq(1L), eq(8L), argThat(form -> form.content().equals("补充情况")));
            when(media.read(1, 8L, 10))
                    .thenReturn(
                            new ConsultationMediaService.Image(new byte[] {1, 2, 3}, "image/png"));
            var photo =
                    mvc.perform(get("/api/v1/consultations/8/images/10")).andReturn().getResponse();
            assertEquals(200, photo.getStatus());
            assertEquals("no-store", photo.getHeader("Cache-Control"));
            assertEquals("nosniff", photo.getHeader("X-Content-Type-Options"));
        } finally {
            RequestContext.clear();
        }
    }
}
