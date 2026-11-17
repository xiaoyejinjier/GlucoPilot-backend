package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.GlobalExceptionHandler;
import com.threadfit.glucopilot.payment.controller.AlipaySandboxController;
import com.threadfit.glucopilot.payment.service.*;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SandboxPaymentControllerTest {
    @Test
    void onlyNotifyIsAnonymousAndUsesExactPlainTextAcknowledgement() throws Exception {
        RequestContext.clear();
        var payments = mock(SandboxPaymentService.class);
        var web =
                MockMvcBuilders.standaloneSetup(
                                new AlipaySandboxController(
                                        mock(SandboxRechargeService.class),
                                        payments,
                                        mock(WalletOrderService.class)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        assertEquals(
                401,
                web.perform(post("/api/v1/wallet/recharge-orders/test/payments"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(
                401,
                web.perform(post("/api/v1/wallet/recharge-orders/test/refresh"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        when(payments.notify(anyMap())).thenReturn(true);
        var result =
                web.perform(
                                post("/api/v1/payments/alipay/sandbox/notify")
                                        .contentType("application/x-www-form-urlencoded")
                                        .param("out_trade_no", "test"))
                        .andReturn()
                        .getResponse();
        assertEquals(200, result.getStatus());
        assertEquals("success", result.getContentAsString());
        assertTrue(result.getContentType().startsWith("text/plain"));
        clearInvocations(payments);
        var duplicate =
                web.perform(
                                post("/api/v1/payments/alipay/sandbox/notify")
                                        .contentType("application/x-www-form-urlencoded")
                                        .param("out_trade_no", "first", "second"))
                        .andReturn()
                        .getResponse();
        assertEquals("failure", duplicate.getContentAsString());
        verifyNoInteractions(payments);
    }
}
