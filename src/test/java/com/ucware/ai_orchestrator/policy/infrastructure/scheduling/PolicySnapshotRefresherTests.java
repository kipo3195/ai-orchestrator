package com.ucware.ai_orchestrator.policy.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryGlobalAiPolicyStore;

class PolicySnapshotRefresherTests {

    private static final Instant REFRESHED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SECOND_REFRESHED_AT = Instant.parse("2026-01-01T00:05:00Z");

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
    void publishesInitializedEmptySnapshotWhenRepositoryReturnsNoPolicies() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllGlobalPolicies()).thenReturn(Map.of());
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));

        refresher.refresh();

        GlobalAiPolicySnapshot snapshot = store.currentSnapshot();
        assertThat(snapshot.initialized()).isTrue();
        assertThat(snapshot.tenantPolicies()).isEmpty();
        assertThat(snapshot.loadedAt()).isEqualTo(REFRESHED_AT);
        assertThat(snapshot.isEnabled("tenant-1")).isFalse();
    }

    @Test
    void replacesPreviousSnapshotWithoutMergingRemovedTenants() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        // 연속으로 사용하면 메서드 호출 순서에 따라 서로 다른 값을 반환
        when(repository.findAllGlobalPolicies())
                .thenReturn(Map.of(
                        "tenant-keep", true,
                        "tenant-remove", true))
                .thenReturn(Map.of(
                        "tenant-keep", false,
                        "tenant-add", true));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(REFRESHED_AT, SECOND_REFRESHED_AT);
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(repository, store, clock);

        refresher.refresh();
        GlobalAiPolicySnapshot previousSnapshot = store.currentSnapshot();

        refresher.refresh();
        GlobalAiPolicySnapshot currentSnapshot = store.currentSnapshot();

        assertThat(currentSnapshot).isNotSameAs(previousSnapshot);
        assertThat(currentSnapshot.loadedAt()).isEqualTo(SECOND_REFRESHED_AT);
        assertThat(currentSnapshot.tenantPolicies()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tenant-keep", false,
                "tenant-add", true));
        assertThat(currentSnapshot.isEnabled("tenant-remove")).isFalse();

        assertThat(previousSnapshot.loadedAt()).isEqualTo(REFRESHED_AT);
        assertThat(previousSnapshot.tenantPolicies()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tenant-keep", true,
                "tenant-remove", true));
    }

    @Test
    void keepsUninitializedSnapshotWhenFirstScheduledRefreshFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);

        // findAllGlobalPolicies()가 호출되면 정책 데이터를 반환하지 않고
        // IllegalStateException을 발생시키도록 Mock repository를 설정한다.
        when(repository.findAllGlobalPolicies())
                .thenThrow(new IllegalStateException("database unavailable"));
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        GlobalAiPolicySnapshot initialSnapshot = store.currentSnapshot();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));

        // scheduledRefresh() 내부에서 findAllGlobalPolicies()가 호출되어 예외가 발생한다.
        // scheduledRefresh()가 이 예외를 처리해 호출자에게 전파하지 않는지 검증한다.
        // 예외가 외부로 전파되면 이 assertion이 실패하고 이후 검증은 실행되지 않는다.
        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        // 예외를 처리했다는 사실만으로는 snapshot 유지가 보장되지 않으므로,
        // Store가 기존 초기 snapshot을 그대로 유지하는지 별도로 검증한다.
        assertThat(store.currentSnapshot()).isSameAs(initialSnapshot);
        assertThat(store.currentSnapshot().initialized()).isFalse();
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

        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        assertThat(store.currentSnapshot()).isSameAs(validSnapshot);
        assertThat(store.currentSnapshot().isEnabled("tenant-1")).isTrue();
        assertThat(store.currentSnapshot().loadedAt()).isEqualTo(REFRESHED_AT);
    }

    @Test
    void keepsLastValidSnapshotWhenNewSnapshotValidationFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        Map<String, Boolean> invalidPolicies = new HashMap<>();
        invalidPolicies.put("tenant-2", null);
        when(repository.findAllGlobalPolicies())
                .thenReturn(Map.of("tenant-1", true))
                .thenReturn(invalidPolicies);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(REFRESHED_AT, SECOND_REFRESHED_AT);
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(repository, store, clock);
        refresher.scheduledRefresh();
        GlobalAiPolicySnapshot validSnapshot = store.currentSnapshot();

        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        assertThat(store.currentSnapshot()).isSameAs(validSnapshot);
        assertThat(store.currentSnapshot().tenantPolicies())
                .containsExactlyInAnyOrderEntriesOf(Map.of("tenant-1", true));
        assertThat(store.currentSnapshot().loadedAt()).isEqualTo(REFRESHED_AT);
    }

    @Test
    void keepsLastValidSnapshotWhenTenantIdValidationFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllGlobalPolicies())
                .thenReturn(Map.of("tenant-1", true))
                .thenReturn(Map.of("  ", true));
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));
        refresher.scheduledRefresh();
        GlobalAiPolicySnapshot validSnapshot = store.currentSnapshot();

        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        assertThat(store.currentSnapshot()).isSameAs(validSnapshot);
        assertThat(store.currentSnapshot().tenantPolicies())
                .containsExactlyInAnyOrderEntriesOf(Map.of("tenant-1", true));
        assertThat(store.currentSnapshot().loadedAt()).isEqualTo(REFRESHED_AT);
    }
}
