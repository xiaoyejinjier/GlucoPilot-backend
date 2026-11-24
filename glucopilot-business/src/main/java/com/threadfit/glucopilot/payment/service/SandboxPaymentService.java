package com.threadfit.glucopilot.payment.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.*;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

/** 支付流程协调：生成付款信息、主动查单与处理回调，两种确认路径统一进入settle完成入账。 */
@Service
@RequiredArgsConstructor
public class SandboxPaymentService {
    private final SandboxRechargeService orders;
    private final SandboxPaymentMapper mapper;
    private final SandboxGateway gateway;

    // 网络调用在数据库事务之外，避免长时间占用账号/余额行锁。
    public Payment pay(long userId, String orderNo) {
        // 【1. 身份与配置】只允许操作本人订单，并核对当前应用、商家与订单快照。
        var order = orders.owned(userId, orderNo);
        orders.checkMerchant(order);
        // 已付款或已关闭的订单只返回最终状态，不重复生成付款入口。
        if (!"PENDING".equals(order.getStatus()))
            return new Payment(
                    orderNo,
                    order.getStatus(),
                    order.getAmountCents(),
                    null,
                    null,
                    order.getExpiresAt(),
                    order.getPaymentMode(),
                    null);
        // 【2. 有效期】本地链接过期后先查付款结果，再决定是否新建充值订单。
        if (order.getExpiresAt() == null || !order.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new BizException(ErrorCode.CONFLICT, "支付码已过期，请先查询付款结果后重新下单");
        if ("PAGE".equals(order.getPaymentMode())) {
            // SDK只生成签名跳转链接。网页是否打开、是否付款均不作为入账依据。
            return new Payment(
                    orderNo,
                    order.getStatus(),
                    order.getAmountCents(),
                    null,
                    null,
                    order.getExpiresAt(),
                    "PAGE",
                    gateway.pagePay(order));
        }
        // 兼容旧扫码订单：没有二维码时才预下单，保存二维码后重新读取订单。
        if (order.getQrCode() == null) {
            String qr = gateway.precreate(order);
            mapper.saveQr(orderNo, qr);
            order = orders.owned(userId, orderNo);
        }
        return new Payment(
                orderNo,
                order.getStatus(),
                order.getAmountCents(),
                order.getQrCode(),
                png(order.getQrCode()),
                order.getExpiresAt());
    }

    /** 前端轮询或点击“查询结果”均走这里，由后端向支付宝确认，前端不能指定支付成功。 */
    public void refresh(long userId, String orderNo) {
        var order = orders.owned(userId, orderNo);
        orders.checkMerchant(order);
        // 只有待支付订单才查；claimQuery在数据库中争取查询窗口，避免多次刷新同时请求支付宝。
        if (!"PENDING".equals(order.getStatus()) || mapper.claimQuery(orderNo) == 0) return;
        Proof proof = gateway.query(order);
        // 查无交易时保持原状态；有凭据才进入事务，统一校验并处理成功、关闭或仍待付款。
        if (proof != null) orders.settle(proof);
    }

    /** 支付宝主动通知入口：验签在前，金额与订单核验、幂等入账在settle内完成。 */
    public boolean notify(Map<String, String> parameters) {
        try {
            if (!gateway.verify(parameters)) return false;
            // 从已验签的通知中提取凭据，不能采用前端自行提交的“已支付”标记。
            orders.settle(
                    new Proof(
                            parameters.get("out_trade_no"),
                            parameters.get("trade_no"),
                            parameters.get("trade_status"),
                            new BigDecimal(parameters.getOrDefault("total_amount", "")),
                            parameters.get("app_id"),
                            parameters.get("seller_id")));
            return true;
        } catch (RuntimeException ex) {
            // 验签、金额解析或入账异常均返回failure，由回调协议和后续查单继续确认。
            return false;
        }
    }

    // 旧扫码入口使用ZXing把二维码内容转换为PNG的Base64；网页付款不经过此方法。
    private String png(String value) {
        if (value == null) return null;
        try {
            var matrix =
                    new MultiFormatWriter()
                            .encode(
                                    value,
                                    BarcodeFormat.QR_CODE,
                                    400,
                                    400,
                                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"));
            var output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "支付码生成失败，请重试");
        }
    }
}
