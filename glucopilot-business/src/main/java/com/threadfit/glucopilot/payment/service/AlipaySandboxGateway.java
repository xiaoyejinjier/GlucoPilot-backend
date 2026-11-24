package com.threadfit.glucopilot.payment.service;

import com.alipay.api.*;
import com.alipay.api.domain.*;
import com.alipay.api.internal.util.AlipayLogger;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.payment.config.AlipaySandboxProperties;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Proof;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.KeyFactory;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * 支付宝充值网关：封装SDK客户端、签名验签、付款链接/二维码生成和主动查单。
 * 本类只负责与支付宝通信，订单状态、余额和流水由SandboxRechargeService在事务内处理。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlipaySandboxGateway implements SandboxGateway {
    static {
        // SDK 默认日志含完整签名请求和响应；改用下方白名单字段定位交易状态。
        AlipayLogger.setNeedEnableLogger(false);
    }

    private final AlipaySandboxProperties properties;

    // 【交易诊断】仅记录阶段、订单号、错误码和已知交易状态，不输出签名或完整支付报文。
    private void logResult(
            String stage, SandboxRecharge order, String code, String subCode, String status) {
        String safeStatus =
                switch (status == null ? "" : status) {
                    case "WAIT_BUYER_PAY", "TRADE_CLOSED", "TRADE_SUCCESS", "TRADE_FINISHED" ->
                            status;
                    case "" -> "N/A";
                    default -> "UNKNOWN";
                };
        String orderNo = order.getOrderNo();
        log.info(
                "Alipay sandbox stage={} orderNo={} code={} subCode={} tradeStatus={}",
                stage,
                orderNo != null && orderNo.matches("[A-Za-z0-9_-]{1,64}") ? orderNo : "INVALID",
                safeCode(code),
                subCode == null || subCode.isBlank() ? "N/A" : safeCode(subCode),
                safeStatus);
    }

    /** 创建沙箱SDK客户端；充值和提现共用这里的网关、签名及验签配置。 */
    protected AlipayClient client() throws AlipayApiException {
        if (!properties.ready()) throw new BizException(ErrorCode.PAYMENT_UNAVAILABLE);
        // 先解析密钥并检查RSA长度，尽早提示配置错误，避免直接把SDK内部异常返回给用户。
        RSAKey applicationPrivateKey = validateKey(properties.getPrivateKey(), true);
        RSAKey alipayPublicKey = validateKey(properties.getPublicKey(), false);
        // 两把密钥来自不同的签名方，模数相同意味着误填了应用公钥。
        if (applicationPrivateKey.getModulus().equals(alipayPublicKey.getModulus())) {
            throw new BizException(
                    ErrorCode.BAD_REQUEST,
                    "ALIPAY_SANDBOX_PUBLIC_KEY 实际为应用公钥，请替换为沙箱平台支付宝公钥",
                    Map.of("stage", "KEY_PAIR_CHECK", "key", "ALIPAY_PUBLIC_KEY"));
        }
        var config = new AlipayConfig();
        // 【固定测试环境】此入口只连接沙箱，不接受前端传入支付网关地址。
        config.setServerUrl(AlipaySandboxProperties.GATEWAY);
        config.setAppId(properties.getAppId());
        // 应用私钥给发出的请求签名；支付宝公钥验证收到的响应，不能误填成应用公钥。
        config.setPrivateKey(key(properties.getPrivateKey()));
        config.setAlipayPublicKey(key(properties.getPublicKey()));
        config.setSignType("RSA2");
        config.setCharset("UTF-8");
        config.setFormat("json");
        // 为连接和读取设置超时；超时不等于付款失败，后续仍应使用原订单号查证。
        config.setConnectTimeout(3000);
        config.setReadTimeout(10000);
        return new DefaultAlipayClient(config);
    }

    // 兼容环境变量中的字面量换行和PEM头尾，整理为SDK需要的Base64密钥内容。
    private String key(String value) {
        return value.replace("\\r", "\r")
                .replace("\\n", "\n")
                .replaceAll("-----[^-]+-----", "")
                .replaceAll("\\s+", "");
    }

    // 私钥按PKCS8解析、公钥按X509解析，并要求至少2048位；这里只检查格式，不输出密钥。
    private RSAKey validateKey(String value, boolean privateKey) {
        try {
            byte[] bytes = Base64.getDecoder().decode(key(value));
            var factory = KeyFactory.getInstance("RSA");
            RSAKey parsed =
                    privateKey
                            ? (RSAKey) factory.generatePrivate(new PKCS8EncodedKeySpec(bytes))
                            : (RSAKey) factory.generatePublic(new X509EncodedKeySpec(bytes));
            if (parsed.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            return parsed;
        } catch (Exception ex) {
            throw new BizException(
                    ErrorCode.BAD_REQUEST,
                    privateKey ? "应用私钥格式不正确，请使用RSA2、2048位、PKCS8私钥" : "支付宝公钥格式不正确，请检查RSA2公钥配置",
                    Map.of(
                            "stage",
                            "KEY_VALIDATION",
                            "key",
                            privateKey ? "APPLICATION_PRIVATE_KEY" : "ALIPAY_PUBLIC_KEY"));
        }
    }

    // 错误码采用白名单/格式过滤，防止把上游异常报文中的敏感参数带回前端或日志。
    private String safeCode(String value) {
        if (value != null) {
            for (var known : AlipayApiErrorEnum.values())
                if (value.equals(known.getErrCode())) return value;
        }
        return value != null
                        && value.matches(
                                "(?:[0-9]{5}|(?:isv\\.|isp\\.|ACQ\\.|SDK_)[A-Za-z0-9_.-]{1,55})")
                ? value
                : "SDK_ERROR";
    }

    // 把支付阶段和安全错误码转换成项目统一异常，供前端显示可定位的失败原因。
    private BizException upstream(String stage, String code, String subCode) {
        String safe = safeCode(subCode == null || subCode.isBlank() ? code : subCode);
        String message =
                switch (safe) {
                    case "SDK_TIMEOUT" -> "连接支付宝沙箱超时，请检查后端网络";
                    case "SDK_NETWORK" -> "无法连接支付宝沙箱，请检查后端网络";
                    case "SDK_TLS_ERROR" -> "支付宝沙箱TLS证书校验失败，请核对系统时间与证书";
                    case "SDK_RESPONSE_SIGNATURE" -> "支付宝响应验签失败，请检查支付宝公钥";
                    case "CHECK_ALIPAY_PUBLIC_KEY_ERROR" ->
                            "ALIPAY_SANDBOX_PUBLIC_KEY 实际为应用公钥，请替换为沙箱平台支付宝公钥";
                    default ->
                            switch (stage) {
                                case "PRECREATE" -> "支付宝沙箱预下单失败：" + safe;
                                case "PAGE_PAY" -> "支付宝沙箱网页付款链接生成失败：" + safe;
                                default -> "支付宝沙箱查单失败：" + safe;
                            };
                };
        return new BizException(
                ErrorCode.PAYMENT_UPSTREAM_ERROR,
                message,
                Map.of("stage", stage, "providerCode", safeCode(code), "providerSubCode", safe));
    }

    // 区分网络、超时、TLS、密钥及验签错误；只返回归类后的信息，不透传原始SDK异常正文。
    BizException sdkFailure(String stage, AlipayApiException exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException)
                return upstream(stage, "SDK_TIMEOUT", null);
            if (cause instanceof javax.net.ssl.SSLException)
                return upstream(stage, "SDK_TLS_ERROR", null);
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException)
                return upstream(stage, "SDK_NETWORK", null);
        }
        String message = String.valueOf(exception.getMessage()).toLowerCase(java.util.Locale.ROOT);
        // SDK的枚举异常构造器不填errCode，只把错误模板写进message。
        // 优先匹配最长的官方模板前缀，避免通用“验签失败”覆盖具体错误。
        // 只返回枚举名，不返回模板中的签名、正文或密钥参数。
        AlipayApiErrorEnum matched = null;
        int matchedLength = 0;
        for (var known : AlipayApiErrorEnum.values()) {
            String template = known.getErrMsg();
            if (template == null) continue;
            String prefix = template.split("%", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (prefix.length() >= 5
                    && prefix.length() > matchedLength
                    && message.contains(prefix)) {
                matched = known;
                matchedLength = prefix.length();
            }
        }
        if (matched != null) {
            String code =
                    matched == AlipayApiErrorEnum.CHECK_SIGN_ERROR
                            ? "SDK_RESPONSE_SIGNATURE"
                            : matched.getErrCode();
            return upstream(stage, code, null);
        }
        if (message.contains("sign check fail")
                || message.contains("check sign fail")
                || message.contains("验签失败")) return upstream(stage, "SDK_RESPONSE_SIGNATURE", null);
        if (message.contains("timed out")) return upstream(stage, "SDK_TIMEOUT", null);
        return upstream(stage, exception.getErrCode(), null);
    }

    /** 兼容已有扫码订单：向支付宝预下单，得到二维码内容；预下单成功不代表已付款。 */
    @Override
    public String precreate(SandboxRecharge order) {
        var model = new AlipayTradePrecreateModel();
        // 使用本系统订单号和已保存金额；分精确转元，防止前端临时修改付款金额。
        model.setOutTradeNo(order.getOrderNo());
        model.setTotalAmount(BigDecimal.valueOf(order.getAmountCents(), 2).toPlainString());
        model.setSubject("GlucoPilot沙箱钱包充值（测试资金）");
        model.setTimeoutExpress("15m");
        model.setSellerId(properties.getSellerId());
        var request = new AlipayTradePrecreateRequest();
        request.setBizModel(model);
        // 有公网回调地址时才携带；未配置时仍可通过主动查单核实交易。
        if (!properties.getNotifyUrl().isBlank()) request.setNotifyUrl(properties.getNotifyUrl());
        try {
            var response = client().execute(request);
            logResult("PRECREATE", order, response.getCode(), response.getSubCode(), null);
            if (!response.isSuccess()
                    || response.getQrCode() == null
                    || response.getQrCode().isBlank())
                throw upstream("PRECREATE", response.getCode(), response.getSubCode());
            return response.getQrCode();
        } catch (AlipayApiException ex) {
            // 不向客户端/日志输出包含密钥或完整支付报文的SDK异常。
            throw sdkFailure("PRECREATE", ex);
        }
    }

    /** 在支付宝官方页面输入账号与支付密码，服务端仅签名订单参数。 */
    @Override
    public String pagePay(SandboxRecharge order) {
        var model = new AlipayTradePagePayModel();
        // 【网页付款参数】以保存的订单号、金额和有效期生成收银台链接，付款密码仅在支付宝页面输入。
        model.setOutTradeNo(order.getOrderNo());
        model.setTotalAmount(BigDecimal.valueOf(order.getAmountCents(), 2).toPlainString());
        model.setSubject("GlucoPilot沙箱钱包充值（测试资金）");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setIntegrationType("PCWEB");
        model.setTimeExpire(
                order.getExpiresAt()
                        .format(
                                java.time.format.DateTimeFormatter.ofPattern(
                                        "yyyy-MM-dd HH:mm:ss")));
        var request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        if (!properties.getNotifyUrl().isBlank()) request.setNotifyUrl(properties.getNotifyUrl());
        try {
            // GET返回完整签名URL，生成链接不调用交易创建接口，也不采信浏览器回跳。
            String url = client().pageExecute(request, "GET").getBody();
            // 返回链接必须属于固定沙箱网关，避免把异常响应作为跳转地址交给小程序。
            if (url == null || !url.startsWith(AlipaySandboxProperties.GATEWAY + "?"))
                throw upstream("PAGE_PAY", "SDK_INVALID_RESPONSE", null);
            logResult("PAGE_PAY", order, null, null, null);
            return url;
        } catch (AlipayApiException ex) {
            throw sdkFailure("PAGE_PAY", ex);
        }
    }

    /** 按本系统订单号主动查单；获得支付宝响应后，交给上层校验交易并决定是否入账。 */
    @Override
    public Proof query(SandboxRecharge order) {
        var model = new AlipayTradeQueryModel();
        model.setOutTradeNo(order.getOrderNo());
        var request = new AlipayTradeQueryRequest();
        request.setBizModel(model);
        try {
            var response = client().execute(request);
            // code=10000 仅代表查单成功；是否付款必须看 trade_status，再由 settle 校验入账。
            logResult(
                    "QUERY",
                    order,
                    response.getCode(),
                    response.getSubCode(),
                    response.getTradeStatus());
            if (!response.isSuccess()) {
                // 尚未形成支付宝交易时可以查无此单；保留本地待支付状态，不据此认定付款成功。
                if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) return null;
                throw upstream("QUERY", response.getCode(), response.getSubCode());
            }
            // query在签名请求的商户上下文内查询；SDK验证支付宝响应签名。
            // Proof保存单号、金额、交易状态及买家UID，后续统一用于入账对账和收款身份识别。
            return new Proof(
                    response.getOutTradeNo(),
                    response.getTradeNo(),
                    response.getTradeStatus(),
                    new BigDecimal(response.getTotalAmount()),
                    properties.getAppId(),
                    properties.getSellerId(),
                    response.getBuyerUserId());
        } catch (AlipayApiException ex) {
            throw sdkFailure("QUERY", ex);
        } catch (NumberFormatException ex) {
            throw upstream("QUERY", "SDK_INVALID_RESPONSE", null);
        }
    }

    /** 异步通知来自公网，必须先用支付宝公钥验签；验签通过后仍需核对订单和金额。 */
    @Override
    public boolean verify(Map<String, String> parameters) {
        if (!properties.ready() || !"RSA2".equals(parameters.get("sign_type"))) return false;
        try {
            return AlipaySignature.rsaCheckV1(
                    parameters, key(properties.getPublicKey()), "UTF-8", "RSA2");
        } catch (AlipayApiException ex) {
            return false;
        }
    }
}
