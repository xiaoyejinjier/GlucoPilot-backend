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

/**
 * 医生提现的支付宝沙箱网关：负责调用转账、查单接口，并把响应转换为对账凭据。
 * 上层 WithdrawalTransferService 执行“先查单 → 必要时同号转账 → 再查单”；
 * 审核、到期判断、冻结金额和订单状态由上层业务服务管理。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlipaySandboxTransferGateway implements SandboxTransferGateway {
    private final AlipaySandboxGateway gateway;
    private final AlipaySandboxProperties properties;

    // 【转账前校验】当前应用、商家必须与申请提现时保存的快照一致，避免换配置后错用旧订单。
    // 收款UID只做格式和非商家自身校验，账号是否真实可用由支付宝确认。
    private void checkContext(WithdrawalOrder order) {
        if (!properties.transferReady()
                || !Objects.equals(properties.getAppId(), order.getSandboxAppId())
                || !Objects.equals(properties.getSellerId(), order.getSandboxSellerId())
                || order.getPayeeUserId() == null
                || !order.getPayeeUserId().matches("2088[0-9]{12,28}")
                || order.getPayeeUserId().equals(order.getSandboxSellerId()))
            throw new BizException(ErrorCode.CONFLICT, "沙箱转账应用、商家或收款账号不匹配");
    }

    // 只保留短错误码，便于日志与前端定位；异常内容不能原样输出密钥或完整支付响应。
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

    /** 查询已有转账；返回null仅表示支付宝明确回复“订单不存在”，不代表转账成功或失败。 */
    @Override
    public Proof query(WithdrawalOrder order) {
        checkContext(order);
        // 【1. 组织查单参数】用本系统的提现订单号定位支付宝转账，产品及场景与提交时一致。
        var model = new AlipayFundTransCommonQueryModel();
        model.setOutBizNo(order.getOrderNo());
        model.setProductCode("TRANS_ACCOUNT_NO_PWD");
        model.setBizScene("DIRECT_TRANSFER");
        var request = new AlipayFundTransCommonQueryRequest();
        request.setBizModel(model);
        try {
            // 【2. 调用SDK】复用固定沙箱网关的客户端，按配置完成RSA2请求签名和响应验签。
            var response = gateway.client().execute(request);
            String status = response.getStatus();
            log.info(
                    "Alipay sandbox transfer stage=QUERY orderNo={} code={} subCode={} status={}",
                    order.getOrderNo(),
                    safeCode(response.getCode()),
                    safeCode(response.getSubCode()),
                    safeCode(status));
            if (!response.isSuccess()) {
                // 明确“查无此单”交给上层判断是否允许发起转账；其他异常不能当作查无此单。
                if (Set.of("ORDER_NOT_EXIST", "FUND_ORDER_NOT_EXIST", "ACQ.TRADE_NOT_EXIST")
                        .contains(String.valueOf(response.getSubCode()))) return null;
                throw rejected(response.getCode(), response.getSubCode());
            }
            // 【3. 返回对账凭据】接口调用成功仍要看转账状态；上层核对单号、金额和收款人后再结算。
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
            // 超时、网络或验签异常统一交给上层记录为待核实，不能据此假定未转账并换号重打。
            throw gateway.sdkFailure("TRANSFER_QUERY", ex);
        } catch (NumberFormatException ex) {
            // 支付宝返回的金额无法解析时，停止对账，不把异常金额当作0元或成功。
            throw rejected("SDK_INVALID_RESPONSE", null);
        }
    }

    /** 提交转账请求；本方法不直接修改钱包余额或将提现标记为完成。 */
    @Override
    public void transfer(WithdrawalOrder order) {
        checkContext(order);
        var model = new AlipayFundTransUniTransferModel();
        // 【1. 幂等编号】同一笔提现始终使用同一个out_biz_no，超时重试也不换号，防止重复打款。
        model.setOutBizNo(order.getOrderNo());
        // 【2. 金额换算】本系统以整数“分”保存金额，精确换算成支付宝要求的“元”，避免浮点误差。
        model.setTransAmount(BigDecimal.valueOf(order.getAmountCents(), 2).toPlainString());
        model.setProductCode("TRANS_ACCOUNT_NO_PWD");
        model.setBizScene("DIRECT_TRANSFER");
        model.setOrderTitle("GlucoPilot医生收入提现（沙箱测试）");
        model.setRemark("仅沙箱测试资金");
        // 【3. 收款身份】使用订单中已保存的支付宝UID，不使用手机号，也不从本次前端请求临时取值。
        var payee = new Participant();
        payee.setIdentityType("ALIPAY_USER_ID");
        payee.setIdentity(order.getPayeeUserId());
        model.setPayeeInfo(payee);
        // 【4. 场景报备】提交配置好的转账场景和问诊收入说明，供支付宝校验对应产品要求。
        model.setTransferSceneName(properties.getTransferSceneName());
        var info = new TransferSceneReportInfo();
        info.setInfoType(properties.getTransferSceneInfoType());
        info.setInfoContent("GlucoPilot问诊服务收入沙箱测试");
        model.setTransferSceneReportInfos(List.of(info));
        var request = new AlipayFundTransUniTransferRequest();
        request.setBizModel(model);
        try {
            // 【5. 提交SDK请求】只记录订单号、错误码和状态，方便排查，不记录密钥或完整响应。
            var response = gateway.client().execute(request);
            log.info(
                    "Alipay sandbox transfer stage=SEND orderNo={} code={} subCode={} status={}",
                    order.getOrderNo(),
                    safeCode(response.getCode()),
                    safeCode(response.getSubCode()),
                    safeCode(response.getStatus()));
            if (!response.isSuccess()) throw rejected(response.getCode(), response.getSubCode());
            // 【6. 再次查单】即使提交返回SUCCESS，上层也会统一查单核对金额、单号及收款人后才清冻结。
        } catch (AlipayApiException ex) {
            // 请求超时可能发生在支付宝已受理之后；上层保持冻结，用原订单号继续查证。
            throw gateway.sdkFailure("TRANSFER", ex);
        }
    }
}
