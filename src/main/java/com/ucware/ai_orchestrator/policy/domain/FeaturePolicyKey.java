package com.ucware.ai_orchestrator.policy.domain;

import java.util.Objects;

public record FeaturePolicyKey(
        String tenantId,
        AiFeature feature) {

    public FeaturePolicyKey {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(feature, "feature must not be null");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
