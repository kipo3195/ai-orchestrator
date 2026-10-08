package com.ucware.ai_orchestrator.policy.domain;

import java.util.Objects;

public record AiExecutionPolicyDecision(
        boolean allowed,
        AiExecutionPolicyReason reason) {

    public AiExecutionPolicyDecision {
        Objects.requireNonNull(reason);
        if (allowed != (reason == AiExecutionPolicyReason.ALLOWED)) {
            throw new IllegalArgumentException("Allowed decision must use ALLOWED reason");
        }
    }

    public static AiExecutionPolicyDecision allow() {
        return new AiExecutionPolicyDecision(true, AiExecutionPolicyReason.ALLOWED);
    }

    public static AiExecutionPolicyDecision rejected(AiExecutionPolicyReason reason) {
        if (reason == AiExecutionPolicyReason.ALLOWED) {
            throw new IllegalArgumentException("Rejected decision cannot use ALLOWED reason");
        }
        return new AiExecutionPolicyDecision(false, reason);
    }
}
