package com.threadfit.glucopilot.wallet.withdrawal.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

public final class WithdrawalDtos {
    private WithdrawalDtos() {}

    public record Apply(
            @NotNull @Min(1) @Max(100000000) Integer amountCents,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId) {}

    public record Review(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String status,
            @NotBlank @Size(max = 500) String note,
            @NotNull @Min(0) Long version) {}

    public record Summary(
            boolean eligible,
            boolean sandbox,
            long availableCents,
            long frozenCents,
            int businessDays,
            String calendarRule) {}

    public record CalendarDay(
            @NotNull LocalDate date, @NotNull Boolean workingDay, @Size(max = 100) String note) {}
}
