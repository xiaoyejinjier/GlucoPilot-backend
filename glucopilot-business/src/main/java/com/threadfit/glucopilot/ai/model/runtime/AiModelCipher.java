package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;

/**
 * 模型密钥的加解密与打码。
 * <p>
 * <b>为什么必须加密而不是"存明文但只给管理员看"</b>：密钥会随库备份、只读从库、导出脚本、
 * 审计日志扩散出去，任一处泄漏都等于把用户健康数据的调用额度交出去。表里那一列从 V1 起就叫
 * {@code api_key_cipher}，注释写着"密文或 KMS 引用，禁止明文"——这个约定在这里落地。
 * <p>
 * <b>算法与格式</b>：AES-256-GCM，随机 12 字节 IV，输出 {@code base64(iv || ciphertext || tag)}。
 * GCM 自带完整性校验，因此"解密失败"同时也意味着密文被篡改——两种情况都当配置不可用处理。
 * <p>
 * <b>主密钥来源</b>：环境变量 {@code AI_CONFIG_MASTER_KEY}（≥16 字节，推荐 32 字节随机值的 base64）。
 * <b>刻意没有默认值</b>：主密钥是"能解开所有密钥的密钥"，给它一个默认值等于没有保护。
 * 缺失时本类不会让应用起不来，而是让"需要解密"的路径显式失败、由上层回落 yaml（见
 * {@link AiRuntimeConfigService}）——这是 2026-10-12 与产品确认过的行为：本地开发不该被主密钥挡住。
 * <p>
 * <b>打码</b>：只保留头 3 位与尾 4 位，中间一律 {@code ****}。列表/详情/审计日志统一走
 * {@link #mask(String)}，绝不把明文或密文回传前端。
 */
@Slf4j
@Component
public class AiModelCipher {

    /** 加密算法标识，落库到 {@code cipher_algo}，为将来换算法（如 KMS 引用）留出判据。 */
    public static final String ALGO_AES_GCM = "AES-GCM";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    /** 当前主密钥版本：轮换时递增，并允许同时保留旧版本解密能力（此处先落单一版本）。 */
    private static final int CURRENT_KEY_VERSION = 1;

    private final String masterKey;
    private final SecureRandom random = new SecureRandom();

    public AiModelCipher(@Value("${AI_CONFIG_MASTER_KEY:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey.trim();
        if (this.masterKey.isEmpty()) {
            log.warn("未配置 AI_CONFIG_MASTER_KEY：后台里已加密的模型密钥将无法解密，"
                    + "这些配置会被跳过并回落到 yaml/环境变量（后台保存密钥时也会被明确拒绝）。");
        } else if (this.masterKey.getBytes(StandardCharsets.UTF_8).length < 16) {
            log.warn("AI_CONFIG_MASTER_KEY 长度不足 16 字节，建议使用 32 字节随机值的 base64");
        }
    }

    /** 主密钥是否可用（后台"保存密钥"前先问这里，缺了就报错而不是存进一个解不开的密文）。 */
    public boolean available() {
        return !masterKey.isEmpty();
    }

    /** 加密明文密钥；主密钥缺失时抛业务异常（明确拒绝，绝不降级成明文存储）。 */
    public String encrypt(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密钥不能为空");
        }
        if (!available()) {
            // 文案刻意写成"照做就能解决"的两条路：本地开发没有运维，只说"让运维配"会让人卡住
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "服务端未配置 AI_CONFIG_MASTER_KEY，无法安全保存密钥。"
                            + "请注入环境变量 AI_CONFIG_MASTER_KEY（32 字节随机值），"
                            + "本机开发也可写在 application-local.yaml 的同名顶层键上，然后重启应用");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 不把明文或异常细节带出去；这里失败基本只可能是主密钥长度不合法（AES 要求 16/24/32 字节）
            throw new BizException(ErrorCode.INTERNAL_ERROR, "密钥加密失败，请检查 AI_CONFIG_MASTER_KEY 长度（16/24/32 字节）");
        }
    }

    /**
     * 解密；失败返回 {@code null}（而不是抛异常）。
     * <p>
     * 返回 null 的语义是"这条配置的密钥不可用"，由调用方决定回落策略——目前是整行跳过 + WARN，
     * 因为拿一个解不开的密钥去发请求，等于带着别人的凭证调用，比"这条配置不生效"危险得多。
     */
    public String decryptOrNull(String cipherText) {
        if (!StringUtils.hasText(cipherText)) {
            return null;
        }
        if (!available()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= GCM_IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec(),
                    new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(all, GCM_IV_BYTES, all.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 打码：{@code sk-abcdefghijklmn → sk-****klmn}；空值返回空串。 */
    public static String mask(String secret) {
        if (!StringUtils.hasText(secret)) {
            return "";
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= 7) {
            return "****";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    public int currentKeyVersion() {
        return CURRENT_KEY_VERSION;
    }

    private SecretKeySpec keySpec() {
        byte[] raw = masterKey.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        // 不足 32 字节时用主密钥自身循环填充：既不改调用方约定，也保证 AES-256 的密钥长度
        for (int i = 0; i < key.length; i++) {
            key[i] = raw[i % raw.length];
        }
        return new SecretKeySpec(key, "AES");
    }
}
