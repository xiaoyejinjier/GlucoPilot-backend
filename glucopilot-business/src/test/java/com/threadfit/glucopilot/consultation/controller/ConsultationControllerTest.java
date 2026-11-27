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
    void topicCatalogueRequiresUserAndUsesExplicitRouteWithNoStore() throws Exception {
        assertEquals(
                401,
                web.perform(get("/api/v1/consultations/topics"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verifyNoInteractions(service);
        var topic = new com.threadfit.glucopilot.consultation.entity.ConsultationTopic();
        topic.setCode("POST_MEAL");
        topic.setName("餐后血糖");
        topic.setGroupName("血糖管理");
        when(service.topics()).thenReturn(java.util.List.of(topic));
        RequestContext.setUserId(1L);
        var response = web.perform(get("/api/v1/consultations/topics")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(
                "POST_MEAL",
                json.readTree(response.getContentAsString())
                        .path("data")
                        .get(0)
                        .path("code")
                        .asString());
        verify(service).topics();
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
        verify(service).list(eq(1L), eq(false), eq(false), eq(true), eq(false), isNull(), isNull(), any());
        assertEquals(
                200,
                web.perform(get("/api/v1/consultations")).andReturn().getResponse().getStatus());
        verify(service).list(eq(1L), eq(false), eq(false), eq(false), eq(false), isNull(), isNull(), any());
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
        verify(service).list(eq(2L), eq(false), eq(false), eq(true), eq(true), isNull(), eq("DIET"), any());
    }

    @Test
    void doctorReviewReadRequiresIdentityAndVersionAndIgnoresExternalUserId() throws Exception {
        String path = "/api/v1/consultation-doctors/me/review/read";
        assertEquals(401, web.perform(put(path).contentType("application/json").content("{\"version\":3}"))
                .andReturn().getResponse().getStatus());
        RequestContext.setUserId(1L);
        for (String body : new String[] {"{}", "{\"version\":-1}"})
            assertEquals(400, web.perform(put(path).contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus());
        var response = web.perform(put(path).param("userId", "2")
                .contentType("application/json").content("{\"version\":\"3\"}"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(service).readDoctorReview(eq(1L), argThat(request -> request.version() == 3L));
    }

    @Test
    void statusAndMineFiltersUseCurrentAccountAndNoStore() throws Exception {
        RequestContext.setUserId(1L);
        var response = web.perform(get("/api/v1/consultations")
                .param("mine", "true").param("status", "REPORT_READY")
                .param("topic", "DIET").param("viewer", "999"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(service).list(eq(1L), eq(true), eq(false), eq(false), eq(false),
                eq("REPORT_READY"), eq("DIET"), any());
    }
}
