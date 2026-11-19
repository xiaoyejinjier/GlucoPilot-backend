package com.threadfit.glucopilot.consultation.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.*;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

class ConsultationControllerTest {
    private MockMvc web;
    private ConsultationService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        RequestContext.clear();
        service = mock(ConsultationService.class);
        when(service.capabilities())
                .thenReturn(
                        new Capabilities("ACTIVE", true, true, true, 100, "CNY", "WALLET_SANDBOX"));
        web =
                MockMvcBuilders.standaloneSetup(
                                new ConsultationController(service, mock(WalletOrderService.class)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void cleanup() {
        RequestContext.clear();
    }

    @Test
    void requiresPatientIdentityRatherThanAcceptingOperatorOrQueryUserId() throws Exception {
        RequestContext.setOpsId(9L);
        assertEquals(
                401,
                web.perform(get("/api/v1/consultations/capabilities?userId=1"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void capabilitiesUseSandboxBalanceAndNoStore() throws Exception {
        RequestContext.setUserId(1L);
        var response =
                web.perform(get("/api/v1/consultations/capabilities")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var data = json.readTree(response.getContentAsString()).path("data");
        assertEquals("WALLET_SANDBOX", data.path("paymentProvider").asString());
        assertEquals(100, data.path("feeCents").asInt());
        assertTrue(data.path("paymentEnabled").asBoolean());
    }

    @Test
    void invalidRequestsNeverReachFinancialWrites() throws Exception {
        RequestContext.setUserId(1L);
        for (String body :
                new String[] {
                    "{\"provider\":\"ALIPAY_SANDBOX\",\"clientRequestId\":\"payment_request_001\"}",
                    "{\"provider\":\"WALLET_SANDBOX\",\"clientRequestId\":null}"
                }) {
            assertEquals(
                    400,
                    web.perform(
                                    post("/api/v1/consultation-orders/order-001/payments")
                                            .contentType("application/json")
                                            .content(body))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
        assertEquals(
                400,
                web.perform(
                                post("/api/v1/consultation-orders")
                                        .contentType("application/json")
                                        .content("{\"reportId\":-1,\"clientRequestId\":\"short\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void hideMineUsesCurrentIdentityAndDefaultsOff() throws Exception {
        RequestContext.setUserId(1L);
        assertEquals(
                200,
                web.perform(
                                get("/api/v1/consultations")
                                        .param("hideMine", "true")
                                        .param("viewer", "999"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verify(service).list(eq(1L), eq(false), eq(false), eq(true), eq(false), isNull(), any());
        assertEquals(
                200,
                web.perform(get("/api/v1/consultations")).andReturn().getResponse().getStatus());
        verify(service).list(eq(1L), eq(false), eq(false), eq(false), eq(false), isNull(), any());
    }

    @Test
    void unclaimedFilterUsesAuthenticatedIdentityAndCanCombineTopicAndHideMine() throws Exception {
        RequestContext.setUserId(2L);
        assertEquals(
                200,
                web.perform(
                                get("/api/v1/consultations")
                                        .param("unclaimedOnly", "true")
                                        .param("hideMine", "true")
                                        .param("topic", "DIET")
                                        .param("viewer", "999"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verify(service).list(eq(2L), eq(false), eq(false), eq(true), eq(true), eq("DIET"), any());
    }
}
