package com.ucware.ai_orchestrator.policy.domain;

import java.util.Objects;

public record UserAiPolicy(
        String tenantId,
        String userId,
        boolean enabled) {

    public UserAiPolicy {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(userId, "userId");
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
