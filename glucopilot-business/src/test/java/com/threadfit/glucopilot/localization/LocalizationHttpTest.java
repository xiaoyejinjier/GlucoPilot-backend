package com.threadfit.glucopilot.localization;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalizationHttpTest {
    private LocalizationService service;
    private MockMvc mvc;
    @BeforeEach void setup(){service=mock(LocalizationService.class);mvc=MockMvcBuilders.standaloneSetup(new LocalizationController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();}
    @AfterEach void cleanup(){RequestContext.clear();}
    @Test void requiresLoginAndUsesCurrentUserWithRequestId() throws Exception {
        String payload="{\"items\":[{\"kind\":\"ARTICLE_PREVIEW\",\"id\":\"10\"}]}";
        mvc.perform(post("/api/v1/localizations/en").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(service);RequestContext.setUserId(2L);RequestContext.setRequestId("localization-test-request");
        when(service.localize(eq(2L),any())).thenReturn(List.of(new LocalizationDtos.Result("ARTICLE_PREVIEW","10","UNAVAILABLE","",java.util.Map.of(),java.util.Map.of())));
        mvc.perform(post("/api/v1/localizations/en").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.requestId").value("localization-test-request")).andExpect(jsonPath("$.data[0].status").value("UNAVAILABLE"));
    }
    @Test void invalidTypesEmptyBatchAndNegativeIdsAreRejectedBeforeService() throws Exception {
        RequestContext.setUserId(2L);
        for(String payload:List.of("{\"items\":[]}","{\"items\":[{\"kind\":\"MESSAGE\",\"id\":\"10\"}]}","{\"items\":[{\"kind\":\"ARTICLE_BODY\",\"id\":\"-1\"}]}"))mvc.perform(post("/api/v1/localizations/en").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
