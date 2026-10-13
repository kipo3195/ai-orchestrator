package com.ucware.ai_orchestrator.policy.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record GlobalAiPolicySnapshot(
        boolean initialized,
        Map<String, Boolean> tenantPolicies,
        Instant loadedAt) {

    // 객체 생성시 검증하는 간결생성자 
    public GlobalAiPolicySnapshot {
        Objects.requireNonNull(tenantPolicies);
        if (initialized) {
            Objects.requireNonNull(loadedAt);
        } else if (loadedAt != null || !tenantPolicies.isEmpty()) {
            throw new IllegalArgumentException("Uninitialized snapshot cannot contain policy data");
        }

        for (Map.Entry<String, Boolean> entry : tenantPolicies.entrySet()) {
            String tenantId = entry.getKey();

            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException(
                        "Global Policy tenantId must not be blank");
            }

            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "Global Policy enabled must not be null");
            }
        }

        tenantPolicies = Map.copyOf(tenantPolicies);
    }

    public static GlobalAiPolicySnapshot uninitialized() {
        return new GlobalAiPolicySnapshot(false, Map.of(), null);
    }

    public static GlobalAiPolicySnapshot initialized(Map<String, Boolean> tenantPolicies, Instant loadedAt) {
        return new GlobalAiPolicySnapshot(true, tenantPolicies, loadedAt);
    }

    public boolean isEnabled(String tenantId) {
        Objects.requireNonNull(tenantId);
        return tenantPolicies.getOrDefault(tenantId, false);
    }
}
