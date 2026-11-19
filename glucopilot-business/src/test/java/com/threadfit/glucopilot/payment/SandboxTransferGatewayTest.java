package com.threadfit.glucopilot.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alipay.api.*;
import com.alipay.api.domain.*;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.service.*;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxTransferGatewayTest {
    private AlipaySandboxProperties properties() {
        var p = new AlipaySandboxProperties();
        p.setEnabled(true);
        p.setAppId("2026000000000001");
        p.setSellerId("2088000000000001");
        p.setPrivateKey("fixture_not_used");
        p.setPublicKey("fixture_not_used");
        return p;
    }

    private WithdrawalOrder order() {
        var o = new WithdrawalOrder();
        o.setOrderNo("WD_fixture_1");
        o.setAmountCents(1000);
        o.setPayeeUserId("2088000000000002");
        o.setSandboxAppId("2026000000000001");
        o.setSandboxSellerId("2088000000000001");
        return o;
    }

    private AlipaySandboxTransferGateway gateway(
            AlipayClient client, AlipaySandboxProperties properties) {
        var shared =
                new AlipaySandboxGateway(properties) {
                    @Override
                    protected AlipayClient client() {
                        return client;
                    }
                };
        return new AlipaySandboxTransferGateway(shared, properties);
    }

    @Test
    void transferUsesPersistedMerchantOrderAmountAndUidWithSameBusinessNumberOnRetry()
            throws Exception {
        var client = mock(AlipayClient.class);
        var response = new AlipayFundTransUniTransferResponse();
        response.setCode("10000");
        response.setStatus("SUCCESS");
        when(client.execute(any(AlipayFundTransUniTransferRequest.class))).thenReturn(response);
        var g = gateway(client, properties());
        g.transfer(order());
        g.transfer(order());
        var requests = ArgumentCaptor.forClass(AlipayFundTransUniTransferRequest.class);
        verify(client, times(2)).execute(requests.capture());
        for (var request : requests.getAllValues()) {
            var model = (AlipayFundTransUniTransferModel) request.getBizModel();
            assertEquals("WD_fixture_1", model.getOutBizNo());
            assertEquals("10.00", model.getTransAmount());
            assertEquals("TRANS_ACCOUNT_NO_PWD", model.getProductCode());
            assertEquals("DIRECT_TRANSFER", model.getBizScene());
            assertEquals("ALIPAY_USER_ID", model.getPayeeInfo().getIdentityType());
            assertEquals("2088000000000002", model.getPayeeInfo().getIdentity());
            assertFalse(model.getTransferSceneName().isBlank());
            assertEquals(1, model.getTransferSceneReportInfos().size());
        }
    }

    @Test
    void queryReturnsAmountRecipientAndStatusButMissingOrderIsNotSuccess() throws Exception {
        var client = mock(AlipayClient.class);
        var response = new AlipayFundTransCommonQueryResponse();
        response.setCode("10000");
        response.setOutBizNo("WD_fixture_1");
        response.setOrderId("provider_1");
        response.setTransAmount("10.00");
        response.setReceiverUserId("2088000000000002");
        response.setStatus("SUCCESS");
        when(client.execute(any(AlipayFundTransCommonQueryRequest.class))).thenReturn(response);
        var g = gateway(client, properties());
        var proof = g.query(order());
        assertEquals("WD_fixture_1", proof.orderNo());
        assertEquals("10.00", proof.amount().toPlainString());
        assertEquals("2088000000000002", proof.receiverUserId());
        response.setCode("40004");
        response.setSubCode("ORDER_NOT_EXIST");
        assertNull(g.query(order()));
        response.setSubCode("PAYER_BALANCE_NOT_ENOUGH");
        response.setSubMsg("sensitive_response_fixture");
        var failure = assertThrows(BizException.class, () -> g.query(order()));
        assertTrue(failure.getMessage().contains("PAYER_BALANCE_NOT_ENOUGH"));
        assertFalse(failure.getMessage().contains("sensitive_response_fixture"));
    }

    @Test
    void mismatchedMerchantOrOwnSellerRecipientNeverMakesNetworkRequest() {
        var client = mock(AlipayClient.class);
        var g = gateway(client, properties());
        var order = order();
        order.setSandboxAppId("2026000000000009");
        assertThrows(BizException.class, () -> g.transfer(order));
        order.setSandboxAppId("2026000000000001");
        order.setPayeeUserId("2088000000000001");
        assertThrows(BizException.class, () -> g.query(order));
        verifyNoInteractions(client);
    }
}
