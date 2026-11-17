package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.threadfit.glucopilot.auth.security.SecurityConfig;
import com.threadfit.glucopilot.auth.security.UserSessionAuthFilter;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.payment.controller.AlipaySandboxController;
import com.threadfit.glucopilot.payment.service.*;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.ObjectMapper;

class SandboxSecurityTest {
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class Beans {
        @Bean
        SandboxPaymentService payments() {
            var payments = mock(SandboxPaymentService.class);
            when(payments.notify(anyMap())).thenReturn(true);
            return payments;
        }

        @Bean
        AlipaySandboxController controller(SandboxPaymentService payments) {
            return new AlipaySandboxController(
                    mock(SandboxRechargeService.class), payments, mock(WalletOrderService.class));
        }

        @Bean
        SecurityFilterChain userChain(HttpSecurity http) throws Exception {
            var json = new JsonUtils(new ObjectMapper());
            return new SecurityConfig(json)
                    .userChain(http, new UserSessionAuthFilter(mock(AuthService.class), json));
        }
    }

    @Test
    void realUserSecurityChainAllowsOnlyExactPostCallbackWithoutLogin() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Beans.class);
            context.refresh();
            var web =
                    MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                            .build();
            assertEquals(
                    200,
                    web.perform(
                                    post("/api/v1/payments/alipay/sandbox/notify")
                                            .contentType("application/x-www-form-urlencoded")
                                            .param("out_trade_no", "test"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            for (String route :
                    new String[] {
                        "/api/v1/wallet/recharge-orders/test/payments",
                        "/api/v1/wallet/recharge-orders/test/refresh",
                        "/api/v1/payments/alipay/sandbox/notify/other"
                    })
                assertEquals(401, web.perform(post(route)).andReturn().getResponse().getStatus());
            assertEquals(
                    401,
                    web.perform(get("/api/v1/payments/alipay/sandbox/notify"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
    }
}
