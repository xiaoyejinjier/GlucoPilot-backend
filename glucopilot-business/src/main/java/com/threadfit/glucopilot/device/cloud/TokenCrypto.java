package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 厂商云 token 应用层 AES-GCM 加解密。密钥来自 {@code DEVICE_CLOUD_TOKEN_AES_KEY}
 *（Base64 编码的 16/24/32 字节），未配置时加解密均拒绝，避免明文落库。
 */
@Component
public class TokenCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenCrypto(@Value("${device.cloud.token-aes-key:}") String keyBase64) {
        if (!StringUtils.hasText(keyBase64)) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(keyBase64.trim());
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("DEVICE_CLOUD_TOKEN_AES_KEY 须为 Base64 编码的 16/24/32 字节 AES 密钥");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isReady() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        if (!StringUtils.hasText(plain)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "待加密内容为空");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭证加密失败");
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        if (!StringUtils.hasText(encoded)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "待解密内容为空");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded.trim());
            if (all.length <= GCM_IV_LENGTH) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "凭证密文损坏");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[all.length - GCM_IV_LENGTH];
            System.arraycopy(all, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭证解密失败");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "云凭证加密密钥未配置");
        }
    }
}
