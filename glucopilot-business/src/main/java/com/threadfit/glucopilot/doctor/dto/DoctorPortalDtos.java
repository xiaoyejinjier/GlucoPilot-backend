package com.threadfit.glucopilot.doctor.dto;

import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.CaseView;
import com.threadfit.glucopilot.consultation.entity.DoctorProfile;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

public final class DoctorPortalDtos {
    private DoctorPortalDtos() {}

    public record CreateAccount(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,40}") String username,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record UpdateAccount(
            @NotNull @Min(0) @Max(1) Integer status, @Size(max = 72) String password) {}

    public record Permissions(@NotNull @Size(max = 3) List<@NotBlank String> permissions) {}

    public record Account(
            Long doctorUserId,
            String realName,
            String hospital,
            String specialty,
            String doctorStatus,
            Long opsId,
            String username,
            Integer accountStatus) {}

    public record IncomeEntry(
            String orderNo,
            Long consultationId,
            Integer amountCents,
            Long balanceAfterCents,
            LocalDateTime createdAt) {}

    public record Workbench(DoctorProfile doctor, PageResult<CaseView> requests) {}

    public record Income(WalletDtos.Summary wallet, PageResult<IncomeEntry> entries) {}
}
