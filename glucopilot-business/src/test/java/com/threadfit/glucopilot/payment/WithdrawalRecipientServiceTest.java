package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.entity.DoctorProfile;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Proof;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.payment.service.SandboxGateway;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalRecipientService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;

class WithdrawalRecipientServiceTest {
    @Test
    void recipientEndpointRequiresCurrentIdentityAndIsNotCacheable() {
        var lookup = mock(WithdrawalRecipientService.class);
        var controller =
                new com.threadfit.glucopilot.wallet.withdrawal.controller
                        .WithdrawalRecipientController(lookup);
        com.threadfit.glucopilot.common.context.RequestContext.clear();
        assertThrows(BizException.class, controller::recipient);
        verifyNoInteractions(lookup);
        try {
            com.threadfit.glucopilot.common.context.RequestContext.setUserId(30L);
            when(lookup.resolve(30L))
                    .thenReturn(
                            new WithdrawalRecipientService.DefaultRecipient(
                                    "2088000000000002", "HISTORY"));
            var response = controller.recipient();
            assertEquals("no-store", response.getHeaders().getCacheControl());
            verify(lookup).resolve(30L);
        } finally {
            com.threadfit.glucopilot.common.context.RequestContext.clear();
        }
    }

    final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
    final SandboxPaymentMapper payments = mock(SandboxPaymentMapper.class);
    final ConsultationMapper consultations = mock(ConsultationMapper.class);
    final SandboxGateway gateway = mock(SandboxGateway.class);
    final AlipaySandboxProperties settings = mock(AlipaySandboxProperties.class);
    final WithdrawalRecipientService service =
            new WithdrawalRecipientService(withdrawals, payments, consultations, gateway, settings);

    @BeforeEach
    void setup() {
        var doctor = new DoctorProfile();
        doctor.setStatus("APPROVED");
        when(consultations.doctor(30L)).thenReturn(doctor);
        when(settings.transferReady()).thenReturn(true);
        when(settings.getAppId()).thenReturn("app");
        when(settings.getSellerId()).thenReturn("2088000000000001");
    }

    SandboxRecharge order() {
        var order = new SandboxRecharge();
        order.setOrderNo("SR1");
        order.setUserId(30L);
        order.setAmountCents(100);
        order.setStatus("PAID");
        order.setPaymentProvider("ALIPAY_SANDBOX");
        order.setSandboxAppId("app");
        order.setSandboxSellerId("2088000000000001");
        order.setProviderTradeNo("TRADE1");
        when(payments.latestPaid(30L, "app", "2088000000000001")).thenReturn(order);
        return order;
    }

    Proof proof(String status, String uid) {
        return new Proof(
                "SR1", "TRADE1", status, new BigDecimal("1.00"), "app", "2088000000000001", uid);
    }

    @Test
    void reusesOnlyTheUsersPreviouslyConfirmedRecipientWithoutCallingAlipay() {
        when(withdrawals.latestPayee(30L, "app", "2088000000000001"))
                .thenReturn("2088000000000002");
        var result = service.resolve(30L);
        assertEquals("HISTORY", result.source());
        assertEquals("2088000000000002", result.payeeUserId());
        verifyNoInteractions(payments, gateway);
    }

    @Test
    void verifiedPaidRechargeSuppliesBuyerWithoutCreditingOrCreatingAnyOrder() {
        var order = order();
        when(gateway.query(order)).thenReturn(proof("TRADE_SUCCESS", "2088000000000002"));
        var result = service.resolve(30L);
        assertEquals("RECHARGE", result.source());
        assertEquals("2088000000000002", result.payeeUserId());
        verify(payments).latestPaid(30L, "app", "2088000000000001");
        verifyNoMoreInteractions(payments);
    }

    @Test
    void rejectsWrongOwnerAndUnconfirmedOrMismatchedPaymentProof() {
        var order = order();
        order.setUserId(22L);
        assertThrows(BizException.class, () -> service.resolve(30L));
        verifyNoInteractions(gateway);
        order.setUserId(30L);
        when(gateway.query(order)).thenReturn(proof("WAIT_BUYER_PAY", "2088000000000002"));
        assertThrows(BizException.class, () -> service.resolve(30L));
        when(gateway.query(order))
                .thenReturn(
                        new Proof(
                                "SR1",
                                "TRADE1",
                                "TRADE_SUCCESS",
                                new BigDecimal("2.00"),
                                "app",
                                "2088000000000001",
                                "2088000000000002"));
        assertThrows(BizException.class, () -> service.resolve(30L));
        when(gateway.query(order))
                .thenReturn(
                        new Proof(
                                "OTHER",
                                "TRADE1",
                                "TRADE_SUCCESS",
                                new BigDecimal("1.00"),
                                "app",
                                "2088000000000001",
                                "2088000000000002"));
        assertThrows(BizException.class, () -> service.resolve(30L));
    }

    @Test
    void missingInvalidOrMerchantBuyerNeverBecomesDefaultAndOrdinaryUsersAreDenied() {
        assertEquals("NONE", service.resolve(30L).source());
        var order = order();
        for (String uid : new String[] {"", "2088000000000001", "12345678900"}) {
            when(gateway.query(order)).thenReturn(proof("TRADE_FINISHED", uid));
            assertNull(service.resolve(30L).payeeUserId());
        }
        assertThrows(BizException.class, () -> service.resolve(22L));
    }
}
