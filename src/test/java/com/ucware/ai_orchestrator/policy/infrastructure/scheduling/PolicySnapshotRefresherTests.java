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

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotData;
import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;
import com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryAiPolicyStore;

class PolicySnapshotRefresherTests {

    private static final Instant REFRESHED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SECOND_REFRESHED_AT = Instant.parse("2026-01-01T00:05:00Z");
    private static final FeaturePolicyKey FEATURE_KEY = new FeaturePolicyKey(
            "tenant-1", AiFeature.CONVERSATION_START);

    @Test
    void publishesGlobalAndFeaturePoliciesInOneSnapshot() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllPolicies()).thenReturn(new PolicySnapshotData(
                Map.of("tenant-1", true), Map.of(FEATURE_KEY, true)));
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));

        refresher.refresh();

        AiPolicySnapshot snapshot = store.currentSnapshot();
        // assertThat - 검증할 값을 지정
        assertThat(snapshot.initialized()).isTrue();
        assertThat(snapshot.loadedAt()).isEqualTo(REFRESHED_AT);
        assertThat(snapshot.isGlobalEnabled("tenant-1")).isTrue();
        assertThat(snapshot.isFeatureEnabled("tenant-1", AiFeature.CONVERSATION_START)).isTrue();
    }

    @Test
    void publishesInitializedEmptySnapshotWhenRepositoryReturnsNoPolicies() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllPolicies()).thenReturn(new PolicySnapshotData(Map.of(), Map.of()));
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));

        refresher.refresh();

        assertThat(store.currentSnapshot().initialized()).isTrue();
        assertThat(store.currentSnapshot().globalPolicies()).isEmpty();
        assertThat(store.currentSnapshot().featurePolicies()).isEmpty();
    }

    @Test
    void replacesWholeSnapshotWithoutMergingRemovedPolicies() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllPolicies())
                .thenReturn(new PolicySnapshotData(
                        Map.of("tenant-1", true), Map.of(FEATURE_KEY, true)))
                .thenReturn(new PolicySnapshotData(
                        Map.of("tenant-2", true), Map.of()));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(REFRESHED_AT, SECOND_REFRESHED_AT);
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(repository, store, clock);

        refresher.refresh();
        AiPolicySnapshot previousSnapshot = store.currentSnapshot();
        refresher.refresh();

        AiPolicySnapshot currentSnapshot = store.currentSnapshot();
        assertThat(currentSnapshot).isNotSameAs(previousSnapshot);
        assertThat(currentSnapshot.globalPolicies()).containsOnlyKeys("tenant-2");
        assertThat(currentSnapshot.featurePolicies()).isEmpty();
        assertThat(currentSnapshot.loadedAt()).isEqualTo(SECOND_REFRESHED_AT);
    }

    @Test
    void keepsUninitializedSnapshotWhenFirstScheduledRefreshFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);

        // 실제 DB에 문제가 없어도 DB 장애가 발생한 상황을 인위적으로 재현
        when(repository.findAllPolicies()).thenThrow(new IllegalStateException("database unavailable"));
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        AiPolicySnapshot initialSnapshot = store.currentSnapshot();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));

        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        // 초기 snapshot을 유지
        assertThat(store.currentSnapshot()).isSameAs(initialSnapshot);
        assertThat(store.currentSnapshot().initialized()).isFalse();
    }

    @Test
    void keepsLastValidSnapshotWhenScheduledRefreshFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        when(repository.findAllPolicies())
                .thenReturn(new PolicySnapshotData(
                        Map.of("tenant-1", true), Map.of(FEATURE_KEY, true)))
                .thenThrow(new IllegalStateException("database unavailable"));
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));
        refresher.scheduledRefresh();
        AiPolicySnapshot validSnapshot = store.currentSnapshot();

        // :: 자체가 메서드를 실행하는 것은 아니고, assertThatCode()가 전달받은 메서드를 실행하는 것이야.
        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        assertThat(store.currentSnapshot()).isSameAs(validSnapshot);
    }

    @Test
    void keepsLastValidSnapshotWhenNewSnapshotValidationFails() {
        PolicySnapshotRepository repository = mock(PolicySnapshotRepository.class);
        Map<FeaturePolicyKey, Boolean> invalidFeaturePolicies = new HashMap<>();
        invalidFeaturePolicies.put(FEATURE_KEY, null);
        when(repository.findAllPolicies())
                .thenReturn(new PolicySnapshotData(
                        Map.of("tenant-1", true), Map.of(FEATURE_KEY, true)))
                .thenAnswer(invocation -> new PolicySnapshotData(
                        Map.of("tenant-1", true), invalidFeaturePolicies));
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        PolicySnapshotRefresher refresher = new PolicySnapshotRefresher(
                repository, store, Clock.fixed(REFRESHED_AT, ZoneOffset.UTC));
        
        // 첫 번째: 정상 정책 로딩
        refresher.scheduledRefresh();
        AiPolicySnapshot validSnapshot = store.currentSnapshot();

        // 두 번째: 비정상 정책 로딩 시도, 내부 로직상 IllegalArgumentException("Feature Policy enabled must not be null");
        assertThatCode(refresher::scheduledRefresh).doesNotThrowAnyException();

        // 기존 정상 Snapshot 유지 확인
        assertThat(store.currentSnapshot()).isSameAs(validSnapshot);
    }
}
