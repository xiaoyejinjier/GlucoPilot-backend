package com.threadfit.glucopilot.user.cancellation.service;

/**
 * 注销前的钱包/资产校验（占位）。
 *
 * <p>业务规则尚未确定：当前实现恒返回通过，仅固定调用点，避免后续把资产校验散落到 submit 各处。
 * 真正的校验逻辑接入后，由实现类决定失败语义（默认建议"阻止注销"，资产丢失不可逆）。
 */
public interface WalletCheckService {

    WalletCheckResult check(Long userId);

    /**
     * 校验结果。
     *
     * @param passed     是否允许继续注销
     * @param reasonCode 不允许时的原因码（可空，供文案映射）
     */
    record WalletCheckResult(boolean passed, String reasonCode) {

        private static final WalletCheckResult PASSED = new WalletCheckResult(true, null);

        public static WalletCheckResult ok() {
            return PASSED;
        }
    }
}
