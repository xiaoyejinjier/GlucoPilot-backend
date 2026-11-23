package com.threadfit.glucopilot.wallet.withdrawal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.wallet.withdrawal.controller.AdminWithdrawalController;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalTransferService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminWithdrawalFilterTest {
    private WithdrawalMapper mapper;
    private MockMvc web;

    @BeforeEach
    void setup() {
        mapper = mock(WithdrawalMapper.class);
        var service = new WithdrawalService(
                mapper, mock(SandboxPaymentMapper.class), mock(ConsultationMapper.class),
                new AlipaySandboxProperties());
        web = MockMvcBuilders.standaloneSetup(
                new AdminWithdrawalController(service, mock(WithdrawalTransferService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void optionalDoctorScopeKeepsStatusAndPagination() throws Exception {
        var response = web.perform(get("/admin/v1/doctor-withdrawals")
                .param("userId", "22").param("status", "APPROVED")
                .param("pageNum", "2").param("pageSize", "10"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(mapper).count(22L, "APPROVED");
        verify(mapper).list(22L, "APPROVED", 10, 10L);
        assertEquals(200, web.perform(get("/admin/v1/doctor-withdrawals")
                .param("status", "PENDING")).andReturn().getResponse().getStatus());
        verify(mapper).count(null, "PENDING");
    }

    @Test
    void invalidDoctorCannotFallBackToAllDoctors() throws Exception {
        for (String id : new String[] {"0", "-1", "bad", "9223372036854775808"}) {
            assertEquals(400, web.perform(get("/admin/v1/doctor-withdrawals")
                    .param("userId", id)).andReturn().getResponse().getStatus());
        }
        verifyNoInteractions(mapper);
    }
}
