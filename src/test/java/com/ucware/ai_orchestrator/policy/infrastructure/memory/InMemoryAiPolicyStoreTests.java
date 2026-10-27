package com.ucware.ai_orchestrator.policy.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;

class InMemoryAiPolicyStoreTests {

    private static final Instant PREVIOUS_LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REPLACEMENT_LOADED_AT = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void startsWithUninitializedSnapshot() {
        // 최초 init
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();

        AiPolicySnapshot snapshot = store.currentSnapshot();

        assertThat(snapshot.initialized()).isFalse();
        assertThat(snapshot.globalPolicies()).isEmpty();
        assertThat(snapshot.featurePolicies()).isEmpty();
        assertThat(snapshot.loadedAt()).isNull();
    }

    @Test
    void concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement() throws Exception {
        InMemoryAiPolicyStore store = new InMemoryAiPolicyStore();
        FeaturePolicyKey featureKey = new FeaturePolicyKey(
                "tenant-1", AiFeature.CONVERSATION_START);
        AiPolicySnapshot previousSnapshot = AiPolicySnapshot.initialized(
                Map.of("tenant-1", true), Map.of(featureKey, false), PREVIOUS_LOADED_AT);
        AiPolicySnapshot replacementSnapshot = AiPolicySnapshot.initialized(
                Map.of("tenant-1", false), Map.of(featureKey, true), REPLACEMENT_LOADED_AT);
        store.replace(previousSnapshot);

        int readerCount = 8;
        int iterationCount = 2_000;
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch ready = new CountDownLatch(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int readerIndex = 0; readerIndex < readerCount; readerIndex++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int iteration = 0; iteration < iterationCount; iteration++) {
                        assertThat(store.currentSnapshot())
                                .isIn(previousSnapshot, replacementSnapshot);
                    }
                    return null;
                }));
            }

            tasks.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int iteration = 0; iteration < iterationCount; iteration++) {
                    store.replace(iteration % 2 == 0
                            ? replacementSnapshot
                            : previousSnapshot);
                }
                store.replace(replacementSnapshot);
                return null;
            }));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            assertThat(store.currentSnapshot()).isSameAs(replacementSnapshot);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
