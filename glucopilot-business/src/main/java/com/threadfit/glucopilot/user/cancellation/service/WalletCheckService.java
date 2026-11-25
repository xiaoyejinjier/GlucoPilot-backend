package com.threadfit.glucopilot.user.cancellation.service;

/**
 * 注销前的钱包/资产校验。
 *
 * <p>提交注销与 worker 到期清理前都会调用：任一未结清资产命中即阻断，返回具体 {@code reasonCode}
 * 供前端文案映射与 worker 二次校验。判定规则见 {@code SandboxWalletCheckService}，按优先级只返回
 * 第一条命中原因。
 */
public interface WalletCheckService {

    /** 钱包存在未提现余额（混合资金：收入 &lt; 余额，含充值余额）。 */
    String BALANCE = "BALANCE";
    /** 存在未提现的问诊收入（纯收入：收入 == 余额）。 */
    String INCOME_PENDING = "INCOME_PENDING";
    /** 有正在处理的提现申请（PENDING/APPROVED，冻结金额随订单一起覆盖）。 */
    String WITHDRAWAL_IN_FLIGHT = "WITHDRAWAL_IN_FLIGHT";
    /** 有未完成的充值订单（PENDING 且未过期）。 */
    String RECHARGE_IN_FLIGHT = "RECHARGE_IN_FLIGHT";

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

        public static WalletCheckResult fail(String reasonCode) {
            return new WalletCheckResult(false, reasonCode);
        }
    }
}
