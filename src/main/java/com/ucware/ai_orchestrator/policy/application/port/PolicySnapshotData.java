package com.ucware.ai_orchestrator.policy.application.port;

import java.util.Map;
import java.util.Objects;

import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;

public record PolicySnapshotData(
        Map<String, Boolean> globalPolicies,
        Map<FeaturePolicyKey, Boolean> featurePolicies) {

    public PolicySnapshotData {
        Objects.requireNonNull(globalPolicies);
        Objects.requireNonNull(featurePolicies);
        globalPolicies = Map.copyOf(globalPolicies);
        featurePolicies = Map.copyOf(featurePolicies);
    }
}
