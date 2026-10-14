package com.ucware.ai_orchestrator.policy.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AiPolicySnapshot(
        boolean initialized,
        Map<String, Boolean> globalPolicies,
        Map<FeaturePolicyKey, Boolean> featurePolicies,
        Instant loadedAt) {

    public AiPolicySnapshot {
        Objects.requireNonNull(globalPolicies);
        Objects.requireNonNull(featurePolicies);
        if (initialized) {
            Objects.requireNonNull(loadedAt);
        } else if (loadedAt != null || !globalPolicies.isEmpty() || !featurePolicies.isEmpty()) {
            throw new IllegalArgumentException("Uninitialized snapshot cannot contain policy data");
        }

        for (Map.Entry<String, Boolean> entry : globalPolicies.entrySet()) {
            String tenantId = entry.getKey();
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("Global Policy tenantId must not be blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Global Policy enabled must not be null");
            }
        }

        for (Map.Entry<FeaturePolicyKey, Boolean> entry : featurePolicies.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("Feature Policy key must not be null");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Feature Policy enabled must not be null");
            }
        }

        globalPolicies = Map.copyOf(globalPolicies);
        featurePolicies = Map.copyOf(featurePolicies);
    }

    public static AiPolicySnapshot uninitialized() {
        return new AiPolicySnapshot(false, Map.of(), Map.of(), null);
    }

    public static AiPolicySnapshot initialized(
            Map<String, Boolean> globalPolicies,
            Map<FeaturePolicyKey, Boolean> featurePolicies,
            Instant loadedAt) {
        return new AiPolicySnapshot(true, globalPolicies, featurePolicies, loadedAt);
    }

    public boolean isGlobalEnabled(String tenantId) {
        Objects.requireNonNull(tenantId);
        return globalPolicies.getOrDefault(tenantId, false);
    }

    public boolean isFeatureEnabled(String tenantId, AiFeature feature) {
        return featurePolicies.getOrDefault(new FeaturePolicyKey(tenantId, feature), false);
    }
}
