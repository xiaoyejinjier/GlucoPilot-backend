package com.threadfit.glucopilot.payment.service;

import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Proof;
import com.threadfit.glucopilot.payment.entity.SandboxRecharge;

import java.util.Map;

public interface SandboxGateway {
    String precreate(SandboxRecharge order);

    Proof query(SandboxRecharge order);

    boolean verify(Map<String, String> parameters);
}
