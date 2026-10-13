package com.ucware.ai_orchestrator.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GlobalAiPolicySnapshotTests {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void copiesTenantPoliciesDefensively() {
        Map<String, Boolean> sourcePolicies = new HashMap<>();
        sourcePolicies.put("tenant-1", true);
        GlobalAiPolicySnapshot snapshot = GlobalAiPolicySnapshot.initialized(sourcePolicies, LOADED_AT);

        sourcePolicies.put("tenant-1", false);
        sourcePolicies.put("tenant-2", true);

        assertThat(snapshot.tenantPolicies())
                .containsExactlyInAnyOrderEntriesOf(Map.of("tenant-1", true));
        assertThat(snapshot.isEnabled("tenant-1")).isTrue();
        assertThat(snapshot.isEnabled("tenant-2")).isFalse();
    }

    @Test
    void exposesUnmodifiableTenantPolicies() {
        GlobalAiPolicySnapshot snapshot = GlobalAiPolicySnapshot.initialized(
                Map.of("tenant-1", true), LOADED_AT);

        assertThatThrownBy(() -> snapshot.tenantPolicies().put("tenant-2", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.tenantPolicies())
                .containsExactlyInAnyOrderEntriesOf(Map.of("tenant-1", true));
    }

    @Test
    void rejectsUninitializedSnapshotWithPolicyData() {
        assertThatThrownBy(() -> new GlobalAiPolicySnapshot(
                false, Map.of("tenant-1", true), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Uninitialized snapshot cannot contain policy data");
    }

    @Test
    void rejectsUninitializedSnapshotWithLoadedAt() {
        assertThatThrownBy(() -> new GlobalAiPolicySnapshot(
                false, Map.of(), LOADED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Uninitialized snapshot cannot contain policy data");
    }

    @Test
    void rejectsNullTenantId() {
        Map<String, Boolean> policies = new HashMap<>();
        policies.put(null, true);

        assertThatThrownBy(() -> GlobalAiPolicySnapshot.initialized(policies, LOADED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Global Policy tenantId must not be blank");
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> GlobalAiPolicySnapshot.initialized(
                Map.of("  ", true), LOADED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Global Policy tenantId must not be blank");
    }
}
