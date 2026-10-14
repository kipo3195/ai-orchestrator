package com.ucware.ai_orchestrator.policy.domain;

public enum AiExecutionPolicyReason {
    ALLOWED,
    GLOBAL_AI_DISABLED,
    GLOBAL_POLICY_UNAVAILABLE,
    USER_AI_DISABLED,
    USER_POLICY_NOT_CONFIGURED,
    USER_POLICY_UNAVAILABLE
}
