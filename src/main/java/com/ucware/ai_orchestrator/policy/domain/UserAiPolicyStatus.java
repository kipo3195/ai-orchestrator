package com.ucware.ai_orchestrator.policy.domain;

public enum UserAiPolicyStatus {
    ENABLED,
    DISABLED,
    NOT_CONFIGURED;

    public static UserAiPolicyStatus from(UserAiPolicy policy) {
        return policy.enabled() ? ENABLED : DISABLED;
    }
}
