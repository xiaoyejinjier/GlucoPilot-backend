package com.threadfit.glucopilot.auth.mail;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import jakarta.annotation.PreDestroy;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通过 SMTP 发送邮箱验证码。账号未配置时同步失败；真实发信异步执行，并复用 Transport。
 */
@Component
@RequiredArgsConstructor
public class EmailCodeMailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeMailSender.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:smtp.qq.com}")
    private String host;

    @Value("${spring.mail.port:587}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${glucopilot.mail.from:}")
    private String fromOverride;

    /** 复用 SMTP 会话，避免每封信重新握手。 */
    private final Object transportLock = new Object();
    private Transport sharedTransport;

    /** 接口侧同步校验，未配置时立即返回业务错误。 */
    public void ensureConfigured() {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "邮件服务未配置，请设置 MAIL_USERNAME/MAIL_PASSWORD");
        }
    }

    /**
     * 异步发信；失败只打日志并执行 onFailure（如清冷却），不回传给已返回的 HTTP。
     */
    @Async("mailTaskExecutor")
    public void sendCodeAsync(String to, String code, Runnable onFailure) {
        try {
            doSend(to, code);
        } catch (Exception e) {
            log.error("异步发送邮箱验证码失败 to={}", to, e);
            if (onFailure != null) {
                try {
                    onFailure.run();
                } catch (Exception callbackEx) {
                    log.warn("邮箱发信失败回调异常 to={}", to, callbackEx);
                }
            }
        }
    }

    private void doSend(String to, String code) throws Exception {
        ensureConfigured();
        String from = StringUtils.hasText(fromOverride) ? fromOverride.trim() : username.trim();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject("GlucoPilot 验证码");
        helper.setText("您的验证码是 " + code + "，5 分钟内有效。如非本人操作请忽略。", false);
        message.saveChanges();

        synchronized (transportLock) {
            try {
                Transport transport = connectedTransport();
                transport.sendMessage(message, message.getAllRecipients());
            } catch (Exception first) {
                // 连接可能被服务端断开，重置后再试一次
                closeTransportQuietly();
                Transport transport = connectedTransport();
                transport.sendMessage(message, message.getAllRecipients());
            }
        }
    }

    private Transport connectedTransport() throws Exception {
        if (sharedTransport != null && sharedTransport.isConnected()) {
            return sharedTransport;
        }
        closeTransportQuietly();
        Session session = resolveSession();
        Transport transport = session.getTransport("smtp");
        transport.connect(host, port, username.trim(), password);
        sharedTransport = transport;
        return sharedTransport;
    }

    private Session resolveSession() {
        if (mailSender instanceof JavaMailSenderImpl impl) {
            return impl.getSession();
        }
        throw new BizException(ErrorCode.BIZ_REJECTED, "邮件发送器类型不支持会话复用");
    }

    private void closeTransportQuietly() {
        if (sharedTransport == null) {
            return;
        }
        try {
            sharedTransport.close();
        } catch (Exception ignored) {
            // ignore
        } finally {
            sharedTransport = null;
        }
    }

    @PreDestroy
    void destroy() {
        synchronized (transportLock) {
            closeTransportQuietly();
        }
    }
}
