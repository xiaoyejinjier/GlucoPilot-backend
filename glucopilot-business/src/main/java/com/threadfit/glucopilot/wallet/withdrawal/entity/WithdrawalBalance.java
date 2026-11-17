package com.threadfit.glucopilot.wallet.withdrawal.entity;

import lombok.Data;

@Data
public class WithdrawalBalance {
    private Long balanceCents;
    private Long frozenCents;
    private Long incomeAvailableCents;
}
