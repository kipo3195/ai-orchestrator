package com.ucware.ai_orchestrator.policy.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryGlobalAiPolicyStore;

class PolicySnapshotRefresherTests {

    private static final Instant REFRESHED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void replacesSnapshotWithAllTenantPolicies() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllGlobalPolicies()).thenReturn(Map.of(
                "tenant-1", true,
                "tenant-2", false));
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));

        refresher.refresh();

        GlobalAiPolicySnapshot snapshot = store.currentSnapshot();
        assertThat(snapshot.initialized()).isTrue();
        assertThat(snapshot.loadedAt()).isEqualTo(REFRESHED_AT);
        assertThat(snapshot.isEnabled("tenant-1")).isTrue();
        assertThat(snapshot.isEnabled("tenant-2")).isFalse();
        assertThat(snapshot.isEnabled("missing-tenant")).isFalse();
    }

    @Test
    void keepsLastValidSnapshotWhenScheduledRefreshFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllGlobalPolicies())
                .thenReturn(Map.of("tenant-1", true))
                .thenThrow(new IllegalStateException("database unavailable"));
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));
        refresher.scheduledRefresh();
        GlobalAiPolicySnapshot validSnapshot = store.currentSnapshot();

        refresher.scheduledRefresh();

        assertThat(store.currentSnapshot()).isSameAs(validSnapshot);
        assertThat(store.currentSnapshot().isEnabled("tenant-1")).isTrue();
    }
}
