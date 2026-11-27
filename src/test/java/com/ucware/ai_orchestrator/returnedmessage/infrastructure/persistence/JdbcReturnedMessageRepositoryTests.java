package com.ucware.ai_orchestrator.returnedmessage.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ucware.ai_orchestrator.result.domain.LifecycleEvent;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;
import com.ucware.ai_orchestrator.returnedmessage.application.port.LifecycleCreationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository.DeliveryRetryResult;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageFailureStage;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

@ActiveProfiles("test")
@JdbcTest
@Import({JdbcReturnedMessageRepository.class, JdbcReturnedMessageRepositoryTests.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcReturnedMessageRepositoryTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

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
    void persistsOnlyRelevantJudgmentsAndNullsEachUnsupportedReason() {
        int index = 0;
        for (var priority : ReturnedMessageTopicsResult.Priority.values()) {
            for (boolean reply : new boolean[] { false, true }) {
                for (boolean action : new boolean[] { false, true }) {
                    String requestId = "judgment-" + index++;
                    repository.create(request("tenant-1", "user-1", requestId,
                            "trigger-" + requestId, "period-" + requestId));
                    Instant now = repository.currentTime();
                    repository.awaitConfirmation("tenant-1", requestId, "selection-" + requestId,
                            1, now, now.plusSeconds(600), "confirmation-1", Duration.ofMinutes(2));
                    repository.startProcessing("tenant-1", "user-1", requestId,
                            "confirmation-1", "execution-1");
                    var assessment = new ReturnedMessageTopicsResult.MessageAssessment(
                            "message-1", "원본 제목", priority, "우선순위 근거",
                            reply, "회신 근거", action, "처리 근거");
                    var result = new ReturnedMessageTopicsResult(
                            List.of(new ReturnedMessageTopicsResult.Topic(
                                    "토픽", "요약", List.of("message-1"))),
                            List.of(assessment), now);
                    assertThat(repository.executionSucceeded("tenant-1", requestId,
                            "execution-1", result, "delivery-1")).isTrue();
                    assertThat(repository.executionSucceeded("tenant-1", requestId,
                            "execution-1", result, "delivery-2")).isFalse();
                    var rows = jdbcTemplate.queryForList(
                            "SELECT * FROM returned_message_assessment_reason WHERE request_id = ?",
                            requestId);
                    if (priority == ReturnedMessageTopicsResult.Priority.UNKNOWN && !reply && !action) {
                        assertThat(rows).isEmpty();
                    } else {
                        assertThat(rows).hasSize(1);
                        var row = rows.get(0);
                        assertThat(row.get("priority_reason")).isEqualTo(
                                priority == ReturnedMessageTopicsResult.Priority.UNKNOWN
                                        ? null : "우선순위 근거");
                        assertThat(row.get("reply_reason")).isEqualTo(reply ? "회신 근거" : null);
                        assertThat(row.get("action_reason")).isEqualTo(action ? "처리 근거" : null);
                    }
                }
            }
        }
    }

    @Test
    void rollsBackExecutionTransitionWhenReasonPersistenceFails() {
        repository.create(request("tenant-1", "user-1", "request-1", "trigger-1", "period-1"));
        Instant now = repository.currentTime();
        repository.awaitConfirmation("tenant-1", "request-1", "selection-1", 1,
                now, now.plusSeconds(600), "confirmation-1", Duration.ofMinutes(2));
        repository.startProcessing("tenant-1", "user-1", "request-1", "confirmation-1", "execution-1");
        var original = result();
        var invalid = new ReturnedMessageTopicsResult(original.topics(),
                List.of(original.messageAssessments().get(0), original.messageAssessments().get(0)), now);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.executionSucceeded(
                "tenant-1", "request-1", "execution-1", invalid, "delivery-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1").orElseThrow().status())
                .isEqualTo(ReturnedMessageStatus.PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM returned_message_assessment_reason", Integer.class)).isZero();
    }

    @Test
    void publicEventRevisionChangesOnlyWhenPublicStateChanges() {
        ReturnedMessageRequest request = request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1");

        assertThat(repository.create(request).lifecycle().lifecycleEvent()).isNull();
        Instant now = repository.currentTime();
        assertThat(repository.awaitConfirmation(
                "tenant-1", "request-1", "selection-1", 1,
                now, now.plusSeconds(600), "confirmation-1", Duration.ofMinutes(2)))
                .isTrue();

        LifecycleEvent awaiting = repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow()
                .lifecycleEvent();
        assertThat(awaiting.type()).isEqualTo("returned_message_topic");
        assertThat(awaiting.lifecycleId()).isEqualTo("request-1");
        assertThat(awaiting.revision()).isEqualTo(1);
        assertThat(awaiting.state()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(awaiting.eventId()).isNotBlank();
        assertThat(awaiting.occurredAt()).isNotNull();
        assertThat(repository.create(request).lifecycle().lifecycleEvent()).isEqualTo(awaiting);

        assertThat(repository.startProcessing(
                "tenant-1", "user-1", "request-1", "confirmation-1", "execution-1"))
                .isTrue();
        LifecycleEvent processing = repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow()
                .lifecycleEvent();
        assertThat(processing.revision()).isEqualTo(2);
        assertThat(processing.state()).isEqualTo("PROCESSING");
        assertThat(processing.eventId()).isNotEqualTo(awaiting.eventId());

        assertThat(repository.tryClaim(
                "tenant-1", "request-1", "worker-1", Duration.ofMinutes(1))).isTrue();
        repository.releaseClaim("tenant-1", "request-1", "worker-1");
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow().lifecycleEvent()).isEqualTo(processing);

        assertThat(repository.executionSucceeded(
                "tenant-1", "request-1", "execution-1", result(), "delivery-1"))
                .isTrue();
        LifecycleEvent topicsReady = repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow()
                .lifecycleEvent();
        assertThat(topicsReady.revision()).isEqualTo(3);
        assertThat(topicsReady.state()).isEqualTo("TOPICS_READY");

        assertThat(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 2,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .isEqualTo(DeliveryRetryResult.RETRY_SCHEDULED);
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow().lifecycleEvent()).isEqualTo(topicsReady);

        assertThat(repository.deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "delivery-1")).isTrue();
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.status()).isEqualTo(ReturnedMessageStatus.COMPLETED);
                    assertThat(lifecycle.lifecycleEvent()).isEqualTo(topicsReady);
                });
    }

    @Test
    void duplicateDeclineKeepsExistingClientEventMetadata() {
        repository.create(request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1"));
        Instant now = repository.currentTime();
        repository.awaitConfirmation(
                "tenant-1", "request-1", "selection-1", 1,
                now, now.plusSeconds(600), "confirmation-1", Duration.ofMinutes(2));

        assertThat(repository.decline(
                "tenant-1", "user-1", "request-1", "period-1",
                "confirmation-1", "selection-1")).isTrue();
        LifecycleEvent declined = repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow()
                .lifecycleEvent();
        assertThat(declined.revision()).isEqualTo(2);
        assertThat(declined.state()).isEqualTo("DECLINED");

        assertThat(repository.decline(
                "tenant-1", "user-1", "request-1", "period-1",
                "confirmation-1", "selection-1")).isFalse();
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1")
                .orElseThrow().lifecycleEvent()).isEqualTo(declined);
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
                "tenant-1", "request-1", "stale-execution", result(), "delivery-1"))
                .isFalse();
        assertThat(repository.executionSucceeded(
                "tenant-1", "request-1", "execution-1", result(), "delivery-1"))
                .isTrue();
        assertThat(repository.executionSucceeded(
                "tenant-1", "request-1", "execution-1", result(), "delivery-1"))
                .isFalse();
        assertThat(repository.deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "wrong-delivery"))
                .isFalse();
        assertThat(repository.deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "delivery-1"))
                .isTrue();

        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().satisfies(lifecycle -> {
                    assertThat(lifecycle.status()).isEqualTo(ReturnedMessageStatus.COMPLETED);
                    assertThat(lifecycle.result()).isEqualTo(result());
                });
    }

    @Test
    void noMessagesTransitionStoresZeroUnreadCount() {
        repository.create(request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1"));

        assertThat(repository.markNoMessages("tenant-1", "request-1")).isTrue();
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().satisfies(lifecycle -> {
                    assertThat(lifecycle.status())
                            .isEqualTo(ReturnedMessageStatus.SKIPPED_NO_MESSAGES);
                    assertThat(lifecycle.unreadCount()).isZero();
                    assertThat(lifecycle.lifecycleEvent().revision()).isEqualTo(1);
                    assertThat(lifecycle.lifecycleEvent().state()).isEqualTo("UNAVAILABLE");
                });
    }

    @Test
    void retryableDeliveryFailureAllowsOneRetryThenFailsDelivery() {
        moveToDeliveryPending("request-1", "trigger-1", "period-1");

        assertThat(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 1,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .isEqualTo(DeliveryRetryResult.RETRY_SCHEDULED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT delivery_retry_count
                FROM returned_message_request
                WHERE tenant_id = 'tenant-1' AND request_id = 'request-1'
                """, Integer.class)).isEqualTo(1);
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().extracting(lifecycle -> lifecycle.status())
                .isEqualTo(ReturnedMessageStatus.DELIVERY_PENDING);

        assertThat(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 1,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .isEqualTo(DeliveryRetryResult.RETRIES_EXHAUSTED);
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().satisfies(lifecycle -> {
                    assertThat(lifecycle.status())
                            .isEqualTo(ReturnedMessageStatus.DELIVERY_FAILED);
                    assertThat(lifecycle.failureStage())
                            .isEqualTo(ReturnedMessageFailureStage.DELIVERY);
                    assertThat(lifecycle.failureReason())
                            .isEqualTo("RESULT_DELIVERY_RETRIES_EXHAUSTED");
                });
    }

    @Test
    void zeroRetriesFailsOnInitialDeliveryFailure() {
        moveToDeliveryPending("request-1", "trigger-1", "period-1");

        assertThat(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 0,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .isEqualTo(DeliveryRetryResult.RETRIES_EXHAUSTED);
        assertThat(repository.findOwned("tenant-1", "user-1", "request-1"))
                .get().extracting(lifecycle -> lifecycle.status())
                .isEqualTo(ReturnedMessageStatus.DELIVERY_FAILED);
    }

    @Test
    void retryableDeliveryFailureRequiresMatchingCorrelation() {
        moveToDeliveryPending("request-1", "trigger-1", "period-1");

        assertThat(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "stale-execution", "delivery-1", 1,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .isEqualTo(DeliveryRetryResult.NOT_UPDATED);
        assertThat(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "stale-delivery", 1,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .isEqualTo(DeliveryRetryResult.NOT_UPDATED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT delivery_retry_count
                FROM returned_message_request
                WHERE tenant_id = 'tenant-1' AND request_id = 'request-1'
                """, Integer.class)).isZero();
    }

    @Test
    void readsLegacyResultPayloadWithoutBreakingRecovery() {
        repository.create(request(
                "tenant-1", "user-1", "request-1", "trigger-1", "period-1"));
        jdbcTemplate.update("""
                UPDATE returned_message_request
                SET status = 'DELIVERY_PENDING',
                    execution_id = 'execution-1',
                    delivery_id = 'delivery-1',
                    result_payload = ?
                WHERE tenant_id = 'tenant-1' AND request_id = 'request-1'
                """, """
                {
                  "topics": [{
                    "topic": "운영 점검",
                    "summary": "확인 요청",
                    "priority": "HIGH",
                    "priorityReason": "기한 임박",
                    "replyRequired": true,
                    "replyReason": "회신 요청",
                    "actionRequired": true,
                    "actionReason": "확인 필요",
                    "messageCount": 2,
                    "sourceMessageRefs": ["message-1", "message-2"]
                  }],
                  "generatedAt": "2026-10-28T01:00:00Z"
                }
                """);

        ReturnedMessageTopicsResult restored = repository.findOwned(
                        "tenant-1", "user-1", "request-1")
                .orElseThrow()
                .result();

        assertThat(restored.topics()).hasSize(1);
        assertThat(restored.messageAssessments()).allMatch(assessment -> assessment.subject() == null);
        assertThat(restored.messageAssessments())
                .extracting(ReturnedMessageTopicsResult.MessageAssessment::messageRef)
                .containsExactly("message-1", "message-2");
        assertThat(restored.messageAssessments())
                .allMatch(assessment ->
                        assessment.priority() == ReturnedMessageTopicsResult.Priority.HIGH);
    }

    @Test
    void readsV2ResultPayloadWithoutSubject() {
        moveToDeliveryPending("request-1", "trigger-1", "period-1");
        jdbcTemplate.update("""
                UPDATE returned_message_request
                SET result_payload = ?
                WHERE tenant_id = 'tenant-1' AND request_id = 'request-1'
                """, """
                {
                  "topics": [],
                  "messageAssessments": [{
                    "messageRef": "message-1",
                    "priority": "NORMAL",
                    "replyRequired": false,
                    "actionRequired": false
                  }],
                  "generatedAt": "2026-10-28T01:00:00Z"
                }
                """);

        ReturnedMessageTopicsResult restored = repository.findOwned(
                "tenant-1", "user-1", "request-1").orElseThrow().result();

        assertThat(restored.messageAssessments()).hasSize(1);
        assertThat(restored.messageAssessments().get(0).messageRef()).isEqualTo("message-1");
        assertThat(restored.messageAssessments().get(0).subject()).isNull();
    }

    private ReturnedMessageTopicsResult result() {
        return new ReturnedMessageTopicsResult(
                List.of(new ReturnedMessageTopicsResult.Topic(
                        "제목 1",
                        "요약",
                        List.of("message-1"))),
                List.of(new ReturnedMessageTopicsResult.MessageAssessment(
                        "message-1",
                        "제목 1",
                        ReturnedMessageTopicsResult.Priority.NORMAL,
                        null,
                        true,
                        "답변 요청이 있습니다.",
                        true,
                        "후속 조치 요청이 있습니다.")),
                Instant.parse("2026-10-28T01:00:00Z"));
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

    private void moveToDeliveryPending(
            String requestId, String triggerId, String absencePeriodId) {
        repository.create(request(
                "tenant-1", "user-1", requestId, triggerId, absencePeriodId));
        Instant now = repository.currentTime();
        repository.awaitConfirmation(
                "tenant-1", requestId, "selection-1", 1,
                now, now.plusSeconds(600), "confirmation-1", Duration.ofMinutes(2));
        repository.startProcessing(
                "tenant-1", "user-1", requestId, "confirmation-1", "execution-1");
        repository.executionSucceeded(
                "tenant-1", requestId, "execution-1", result(), "delivery-1");
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
