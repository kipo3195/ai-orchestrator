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

import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;

class InMemoryGlobalAiPolicyStoreTests {

    private static final Instant PREVIOUS_LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REPLACEMENT_LOADED_AT = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void startsWithUninitializedSnapshot() {
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();

        GlobalAiPolicySnapshot snapshot = store.currentSnapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.initialized()).isFalse();
        assertThat(snapshot.tenantPolicies()).isEmpty();
        assertThat(snapshot.loadedAt()).isNull();
    }

    @Test
    void concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement() throws Exception {
        InMemoryGlobalAiPolicyStore store = new InMemoryGlobalAiPolicyStore();
        GlobalAiPolicySnapshot previousSnapshot = GlobalAiPolicySnapshot.initialized(
                Map.of(
                        "tenant-a", true,
                        "tenant-b", false),
                PREVIOUS_LOADED_AT);
        GlobalAiPolicySnapshot replacementSnapshot = GlobalAiPolicySnapshot.initialized(
                Map.of(
                        "tenant-a", false,
                        "tenant-b", true),
                REPLACEMENT_LOADED_AT);
        store.replace(previousSnapshot);

        int readerCount = 8;
        int iterationCount = 2_000;
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch ready = new CountDownLatch(readerCount + 1); // 모든 작업 스레드가 준비되었는지 확인
        CountDownLatch start = new CountDownLatch(1); // 준비된 스레드들을 한꺼번에 실행
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int readerIndex = 0; readerIndex < readerCount; readerIndex++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown(); // ready의 카운트를 1 감소
                    start.await(); // start의 카운트가 0이 될 때까지 현재 스레드가 대기해.

                    for (int iteration = 0; iteration < iterationCount; iteration++) {
                        GlobalAiPolicySnapshot observedSnapshot = store.currentSnapshot();

                        assertThat(observedSnapshot)
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

            // 최대 5초 기다림 (기다리는 동안 모든 executor의 countdown 실행 8(reader) + 1(writer))되면 isTrue.
            // await()의 반환값 5초 이내에 카운트가 0이 됨 true, 0이 아니면 False
            // 최대 대기 시간
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
