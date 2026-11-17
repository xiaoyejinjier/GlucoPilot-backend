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

@Service
@RequiredArgsConstructor
public class SandboxPaymentService {
    private final SandboxRechargeService orders;
    private final SandboxPaymentMapper mapper;
    private final SandboxGateway gateway;

    // 网络调用在数据库事务之外，避免长时间占用账号/余额行锁。
    public Payment pay(long userId, String orderNo) {
        var order = orders.owned(userId, orderNo);
        orders.checkMerchant(order);
        if (!"PENDING".equals(order.getStatus()))
            return new Payment(
                    orderNo,
                    order.getStatus(),
                    order.getAmountCents(),
                    null,
                    null,
                    order.getExpiresAt());
        if (order.getExpiresAt() == null || !order.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new BizException(ErrorCode.CONFLICT, "支付码已过期，请先查询付款结果后重新下单");
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

    public void refresh(long userId, String orderNo) {
        var order = orders.owned(userId, orderNo);
        orders.checkMerchant(order);
        if (!"PENDING".equals(order.getStatus()) || mapper.claimQuery(orderNo) == 0) return;
        Proof proof = gateway.query(order);
        if (proof != null) orders.settle(proof);
    }

    public boolean notify(Map<String, String> parameters) {
        try {
            if (!gateway.verify(parameters)) return false;
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
            return false;
        }
    }

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
