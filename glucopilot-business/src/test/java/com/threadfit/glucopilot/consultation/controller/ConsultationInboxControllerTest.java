package com.threadfit.glucopilot.consultation.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.consultation.service.ConsultationConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConsultationInboxControllerTest {
    @Test
    void identityComesFromLoginAndReadRequiresPositiveCursor() throws Exception {
        var service=mock(ConsultationConversationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ConsultationInboxController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        try {
            RequestContext.clear();
            assertEquals(401,mvc.perform(get("/api/v1/consultation-conversations")).andReturn().getResponse().getStatus());
            RequestContext.setUserId(1L);
            var response=mvc.perform(get("/api/v1/consultation-conversations")).andReturn().getResponse();
            assertEquals(200,response.getStatus());
            assertEquals("no-store",response.getHeader("Cache-Control"));
            assertEquals(400,mvc.perform(put("/api/v1/consultations/8/messages/read").contentType("application/json").content("{\"throughId\":0}")).andReturn().getResponse().getStatus());
            assertEquals(400,mvc.perform(put("/api/v1/consultations/8/messages/read").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
            assertEquals(200,mvc.perform(put("/api/v1/consultations/8/messages/read").contentType("application/json").content("{\"throughId\":7,\"userId\":999}")).andReturn().getResponse().getStatus());
            verify(service).read(1,8,7);
        } finally {RequestContext.clear();}
    }
}
