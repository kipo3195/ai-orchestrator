package com.ucware.ai_orchestrator.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AiPolicySnapshotTests {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void copiesGlobalAndFeaturePoliciesDefensively() {
        Map<String, Boolean> globalPolicies = new HashMap<>();
        Map<FeaturePolicyKey, Boolean> featurePolicies = new HashMap<>();
        FeaturePolicyKey featureKey = new FeaturePolicyKey("tenant-1", AiFeature.CONVERSATION_START);
        globalPolicies.put("tenant-1", true);
        featurePolicies.put(featureKey, true);

        AiPolicySnapshot snapshot = AiPolicySnapshot.initialized(
                globalPolicies, featurePolicies, LOADED_AT);
        globalPolicies.put("tenant-1", false);
        featurePolicies.put(featureKey, false);

        // globalPolicies.put("tenant-1", false);하더라도 True - 방어적 복사 (copyOf)
        assertThat(snapshot.isGlobalEnabled("tenant-1")).isTrue();
        assertThat(snapshot.isFeatureEnabled("tenant-1", AiFeature.CONVERSATION_START)).isTrue();
        // assertThatThrownBy - 실제로 실행하고 발생한 예외를 확인한다. + isInstanceOf를 통해 발생한 예외의 타입을 검증한다. 
        assertThatThrownBy(() -> snapshot.globalPolicies().put("tenant-2", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.featurePolicies().put(featureKey, false))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingPoliciesDefaultToDisabled() {
        AiPolicySnapshot snapshot = AiPolicySnapshot.initialized(Map.of(), Map.of(), LOADED_AT);

        assertThat(snapshot.isGlobalEnabled("tenant-1")).isFalse();
        assertThat(snapshot.isFeatureEnabled("tenant-1", AiFeature.CONVERSATION_START)).isFalse();
    }

    @Test
    void rejectsUninitializedSnapshotWithAnyPolicyDataOrLoadedAt() {
        FeaturePolicyKey featureKey = new FeaturePolicyKey("tenant-1", AiFeature.CONVERSATION_START);

        assertThatThrownBy(() -> new AiPolicySnapshot(
                false, Map.of("tenant-1", true), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiPolicySnapshot(
                false, Map.of(), Map.of(featureKey, true), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiPolicySnapshot(
                false, Map.of(), Map.of(), LOADED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesGlobalAndFeatureEntries() {
        Map<String, Boolean> invalidGlobalPolicies = new HashMap<>();
        invalidGlobalPolicies.put("tenant-1", null);
        Map<FeaturePolicyKey, Boolean> invalidFeaturePolicies = new HashMap<>();
        invalidFeaturePolicies.put(
                new FeaturePolicyKey("tenant-1", AiFeature.CONVERSATION_START), null);

        assertThatThrownBy(() -> AiPolicySnapshot.initialized(
                invalidGlobalPolicies, Map.of(), LOADED_AT))
                .hasMessage("Global Policy enabled must not be null");
        assertThatThrownBy(() -> AiPolicySnapshot.initialized(
                Map.of(), invalidFeaturePolicies, LOADED_AT))
                .hasMessage("Feature Policy enabled must not be null");
    }
}
