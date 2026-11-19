package com.threadfit.glucopilot.payment.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.domain.*;
import com.alipay.api.request.*;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class AlipaySandboxTransferGateway implements SandboxTransferGateway {
    private final AlipaySandboxGateway gateway;
    private final AlipaySandboxProperties properties;

    private void checkContext(WithdrawalOrder order) {
        if (!properties.transferReady()
                || !Objects.equals(properties.getAppId(), order.getSandboxAppId())
                || !Objects.equals(properties.getSellerId(), order.getSandboxSellerId())
                || order.getPayeeUserId() == null
                || !order.getPayeeUserId().matches("2088[0-9]{12,28}")
                || order.getPayeeUserId().equals(order.getSandboxSellerId()))
            throw new BizException(ErrorCode.CONFLICT, "沙箱转账应用、商家或收款账号不匹配");
    }

    public static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "SDK_ERROR";
    }

    private BizException rejected(String code, String subCode) {
        String safe = safeCode(subCode == null ? code : subCode);
        return new BizException(
                ErrorCode.PAYMENT_UPSTREAM_ERROR,
                "支付宝沙箱转账待核实：" + safe,
                Map.of("stage", "TRANSFER", "providerCode", safe));
    }

    @Override
    public Proof query(WithdrawalOrder order) {
        checkContext(order);
        var model = new AlipayFundTransCommonQueryModel();
        model.setOutBizNo(order.getOrderNo());
        model.setProductCode("TRANS_ACCOUNT_NO_PWD");
        model.setBizScene("DIRECT_TRANSFER");
        var request = new AlipayFundTransCommonQueryRequest();
        request.setBizModel(model);
        try {
            var response = gateway.client().execute(request);
            String status = response.getStatus();
            log.info(
                    "Alipay sandbox transfer stage=QUERY orderNo={} code={} subCode={} status={}",
                    order.getOrderNo(),
                    safeCode(response.getCode()),
                    safeCode(response.getSubCode()),
                    safeCode(status));
            if (!response.isSuccess()) {
                if (Set.of("ORDER_NOT_EXIST", "FUND_ORDER_NOT_EXIST", "ACQ.TRADE_NOT_EXIST")
                        .contains(String.valueOf(response.getSubCode()))) return null;
                throw rejected(response.getCode(), response.getSubCode());
            }
            return new Proof(
                    response.getOutBizNo(),
                    response.getOrderId(),
                    response.getPayFundOrderId(),
                    status,
                    response.getTransAmount() == null
                            ? null
                            : new BigDecimal(response.getTransAmount()),
                    response.getReceiverUserId(),
                    safeCode(response.getErrorCode()));
        } catch (AlipayApiException ex) {
            throw gateway.sdkFailure("TRANSFER_QUERY", ex);
        } catch (NumberFormatException ex) {
            throw rejected("SDK_INVALID_RESPONSE", null);
        }
    }

    @Override
    public void transfer(WithdrawalOrder order) {
        checkContext(order);
        var model = new AlipayFundTransUniTransferModel();
        // 超时重试也使用原提现编号，绝不换号再打一笔。
        model.setOutBizNo(order.getOrderNo());
        model.setTransAmount(BigDecimal.valueOf(order.getAmountCents(), 2).toPlainString());
        model.setProductCode("TRANS_ACCOUNT_NO_PWD");
        model.setBizScene("DIRECT_TRANSFER");
        model.setOrderTitle("GlucoPilot医生收入提现（沙箱测试）");
        model.setRemark("仅沙箱测试资金");
        var payee = new Participant();
        payee.setIdentityType("ALIPAY_USER_ID");
        payee.setIdentity(order.getPayeeUserId());
        model.setPayeeInfo(payee);
        model.setTransferSceneName(properties.getTransferSceneName());
        var info = new TransferSceneReportInfo();
        info.setInfoType(properties.getTransferSceneInfoType());
        info.setInfoContent("GlucoPilot问诊服务收入沙箱测试");
        model.setTransferSceneReportInfos(List.of(info));
        var request = new AlipayFundTransUniTransferRequest();
        request.setBizModel(model);
        try {
            var response = gateway.client().execute(request);
            log.info(
                    "Alipay sandbox transfer stage=SEND orderNo={} code={} subCode={} status={}",
                    order.getOrderNo(),
                    safeCode(response.getCode()),
                    safeCode(response.getSubCode()),
                    safeCode(response.getStatus()));
            if (!response.isSuccess()) throw rejected(response.getCode(), response.getSubCode());
            // 即使提交返回SUCCESS，也统一再查单核对金额、单号及收款人后才清冻结。
        } catch (AlipayApiException ex) {
            throw gateway.sdkFailure("TRANSFER", ex);
        }
    }
}
