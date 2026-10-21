package com.ucware.ai_orchestrator.returnedmessage.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;
import com.ucware.ai_orchestrator.returnedmessage.application.port.LifecycleCreationResult;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;

@JdbcTest
@Import(JdbcReturnedMessageRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcReturnedMessageRepositoryTests {

    @Autowired
    private JdbcReturnedMessageRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM returned_message_selection_item");
        jdbcTemplate.update("DELETE FROM returned_message_selection");
        jdbcTemplate.update("DELETE FROM returned_message_request");
    }

    @Test
    void distinguishesRetryPeriodDuplicateTriggerConflictAndTenantIsolation() {
        ReturnedMessageRequest original = request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1");

        assertThat(repository.create(original).outcome())
                .isEqualTo(LifecycleCreationResult.Outcome.CREATED);
        assertThat(repository.create(original).outcome())
                .isEqualTo(LifecycleCreationResult.Outcome.ALREADY_EXISTS);
        assertThat(repository.create(request(
                "tenant-1", "user-1", "request-2", "trigger-2", "period-1")).outcome())
                .isEqualTo(LifecycleCreationResult.Outcome.ALREADY_EXISTS);
        assertThat(repository.create(request(
                "tenant-1", "user-2", "request-3", "trigger-1", "period-2")).outcome())
                .isEqualTo(LifecycleCreationResult.Outcome.CONFLICT);
        assertThat(repository.create(request(
                "tenant-2", "user-1", "request-1", "trigger-1", "period-1")).outcome())
                .isEqualTo(LifecycleCreationResult.Outcome.CREATED);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM returned_message_request", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentCreationGrantsInitialWorkToOneCaller() throws Exception {
        ReturnedMessageRequest request = request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<LifecycleCreationResult>> futures = List.of(
                    executor.submit(() -> createAfterBarrier(request, ready, start)),
                    executor.submit(() -> createAfterBarrier(request, ready, start)));
            ready.await();
            start.countDown();

            List<LifecycleCreationResult.Outcome> outcomes = futures.stream()
                    .map(this::get)
                    .map(LifecycleCreationResult::outcome)
                    .toList();
            assertThat(outcomes).containsExactlyInAnyOrder(
                    LifecycleCreationResult.Outcome.CREATED,
                    LifecycleCreationResult.Outcome.ALREADY_EXISTS);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM returned_message_request", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmationAndExecutionTransitionsUseDatabaseExpirationAndCorrelation() {
        ReturnedMessageRequest request = request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1");
        repository.create(request);
        Instant now = repository.currentTime();

        assertThat(repository.awaitConfirmation(
                "tenant-1", "request-1", "selection-1", 3,
                now, now.plusSeconds(600), "confirmation-1", Duration.ofMinutes(2)))
                .isTrue();
        assertThat(repository.startProcessing(
                "tenant-1", "user-1", "request-1", "confirmation-1", "execution-1"))
                .isTrue();
        assertThat(repository.executionSucceeded(
                "tenant-1", "request-1", "stale-execution", "result-1", "delivery-1"))
                .isFalse();
        assertThat(repository.executionSucceeded(
                "tenant-1", "request-1", "execution-1", "result-1", "delivery-1"))
                .isTrue();
        assertThat(repository.executionSucceeded(
                "tenant-1", "request-1", "execution-1", "result-1", "delivery-1"))
                .isFalse();
        assertThat(repository.deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "wrong-delivery"))
                .isFalse();
        assertThat(repository.deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "delivery-1"))
                .isTrue();

        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().extracting(lifecycle -> lifecycle.status())
                .isEqualTo(ReturnedMessageStatus.COMPLETED);
    }

    @Test
    void expiredConfirmationIsRejectedBeforeSweeperAndBatchExpirationIsBounded() {
        for (int index = 1; index <= 3; index++) {
            String suffix = Integer.toString(index);
            repository.create(request(
                    "tenant-1", "user-" + suffix, "request-" + suffix,
                    "trigger-" + suffix, "period-" + suffix));
            Instant now = repository.currentTime();
            repository.awaitConfirmation(
                    "tenant-1", "request-" + suffix, "selection-" + suffix, 1,
                    now, now.plusSeconds(600), "confirmation-" + suffix,
                    Duration.ofMinutes(2));
        }
        jdbcTemplate.update("""
                UPDATE returned_message_request
                SET confirmation_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                """);

        assertThat(repository.startProcessing(
                "tenant-1", "user-1", "request-1", "confirmation-1", "execution-1"))
                .isFalse();
        assertThat(repository.expireConfirmations(2)).isEqualTo(2);
        assertThat(repository.expireConfirmations(2)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM returned_message_request WHERE status = 'EXPIRED'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void recoveryClaimHasSingleOwnerAndCanBeTakenAfterLeaseExpires() {
        repository.create(request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1"));

        assertThat(repository.tryClaim(
                "tenant-1", "request-1", "worker-1", Duration.ofMinutes(1))).isTrue();
        assertThat(repository.tryClaim(
                "tenant-1", "request-1", "worker-2", Duration.ofMinutes(1))).isFalse();

        jdbcTemplate.update("""
                UPDATE returned_message_request
                SET claim_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE tenant_id = ? AND request_id = ?
                """, "tenant-1", "request-1");
        assertThat(repository.tryClaim(
                "tenant-1", "request-1", "worker-2", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void detectedLifecycleCanExpireWithoutStartingMessageSelection() {
        repository.create(request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1"));
        jdbcTemplate.update("""
                UPDATE returned_message_request
                SET trigger_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE tenant_id = ? AND request_id = ?
                """, "tenant-1", "request-1");

        assertThat(repository.expireTriggerIfDue("tenant-1", "request-1")).isTrue();
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().extracting(lifecycle -> lifecycle.status())
                .isEqualTo(ReturnedMessageStatus.EXPIRED);
    }

    private LifecycleCreationResult createAfterBarrier(
            ReturnedMessageRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.create(request);
    }

    private LifecycleCreationResult get(Future<LifecycleCreationResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ReturnedMessageRequest request(
            String tenantId,
            String userId,
            String requestId,
            String triggerId,
            String absencePeriodId) {
        Instant returnedAt = Instant.now().minusSeconds(60);
        return new ReturnedMessageRequest(
                requestId,
                triggerId,
                tenantId,
                userId,
                absencePeriodId,
                returnedAt.minusSeconds(600),
                returnedAt,
                returnedAt,
                returnedAt.plusSeconds(3600),
                RoutingRef.forClientSession(
                        tenantId,
                        userId,
                        "target-" + userId,
                        new RoutingScope("ABSENCE_PERIOD", absencePeriodId),
                        1));
    }
}
