package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.result.domain.RoutingRef;
import com.ucware.ai_orchestrator.result.domain.RoutingScope;
import com.ucware.ai_orchestrator.returnedmessage.application.port.LifecycleCreationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageConfirmationSender;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ConfirmationDeliveryStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageProperties;

class ReturnedMessageTriggerServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");

    private ReturnedMessageRepository repository;
    private ReturnedMessageQuery messageQuery;
    private ReturnedMessageConfirmationSender confirmationSender;
    private ReturnedMessageAccessEvaluator accessEvaluator;
    private ReturnedMessageTriggerService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReturnedMessageRepository.class);
        messageQuery = mock(ReturnedMessageQuery.class);
        confirmationSender = mock(ReturnedMessageConfirmationSender.class);
        accessEvaluator = mock(ReturnedMessageAccessEvaluator.class);
        service = new ReturnedMessageTriggerService(
                repository,
                messageQuery,
                confirmationSender,
                accessEvaluator,
                new ReturnedMessageProperties(
                        true,
                        Duration.ofMinutes(5),
                        Duration.ofDays(7),
                        Duration.ofHours(24),
                        Duration.ofMinutes(2),
                        Duration.ofSeconds(30),
                        100,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        100));
    }

    @Test
    void positiveConnectionTypeIsAcceptedWithoutInterpretingLegacyMeaning() {
        ReturnedMessageRequest request = request(3);
        ReturnedMessageLifecycle detected = lifecycle(ReturnedMessageStatus.DETECTED, null, null);
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateMessageSelection(any(), any(), any()))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.allow());
        when(repository.create(request)).thenReturn(LifecycleCreationResult.alreadyExists(detected));

        ReturnedMessageOperationResult result = service.handle(request);

        assertThat(result.outcome()).isEqualTo(ReturnedMessageOperationResult.Outcome.EXISTING);
        verify(repository).create(request);
        verifyNoInteractions(messageQuery, confirmationSender);
    }

    @Test
    void missingOrNonPositiveConnectionTypeIsRejectedAsSourceContractViolation() {
        assertThatThrownBy(() -> service.handle(request(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handle(request(0)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, messageQuery, confirmationSender, accessEvaluator);
    }

    @Test
    void exactMinimumAwayDurationIsEligibleButDuplicateDoesNotQueryAgain() {
        ReturnedMessageRequest request = request(1);
        ReturnedMessageLifecycle detected = lifecycle(ReturnedMessageStatus.DETECTED, null, null);
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateMessageSelection(any(), any(), any()))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.allow());
        when(repository.create(request)).thenReturn(LifecycleCreationResult.alreadyExists(detected));

        ReturnedMessageOperationResult result = service.handle(request);

        assertThat(result.outcome()).isEqualTo(ReturnedMessageOperationResult.Outcome.EXISTING);
        verifyNoInteractions(messageQuery, confirmationSender);
    }

    @Test
    void noUnreadMessagesTransitionsWithoutSendingConfirmation() {
        ReturnedMessageRequest request = request(1);
        ReturnedMessageLifecycle detected = lifecycle(ReturnedMessageStatus.DETECTED, null, null);
        ReturnedMessageLifecycle skipped = lifecycle(
                ReturnedMessageStatus.SKIPPED_NO_MESSAGES, null, null);
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateMessageSelection(any(), any(), any()))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.allow());
        when(repository.create(request)).thenReturn(LifecycleCreationResult.created(detected));
        when(messageQuery.selectUnreadMessages(any())).thenReturn(
                new ReturnedMessageQuery.MessageSelection("period-1", 0, null, null, null));
        when(repository.markNoMessages("tenant-1", "request-1")).thenReturn(true);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(skipped));

        ReturnedMessageOperationResult result = service.handle(request);

        assertThat(result.lifecycle().status()).isEqualTo(ReturnedMessageStatus.SKIPPED_NO_MESSAGES);
        verify(confirmationSender, never()).send(any());
    }

    @Test
    void selectionIsPersistedBeforeConfirmationIsSent() {
        ReturnedMessageRequest request = request(1);
        ReturnedMessageLifecycle detected = lifecycle(ReturnedMessageStatus.DETECTED, null, null);
        ReturnedMessageLifecycle awaiting = lifecycle(
                ReturnedMessageStatus.AWAITING_CONFIRMATION, "confirmation-1", "selection-1");
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateMessageSelection(any(), any(), any()))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.allow());
        when(repository.create(request)).thenReturn(LifecycleCreationResult.created(detected));
        when(messageQuery.selectUnreadMessages(any())).thenReturn(
                new ReturnedMessageQuery.MessageSelection(
                        "period-1", 3, "selection-1", NOW, NOW.plusSeconds(600)));
        when(repository.awaitConfirmation(
                eq("tenant-1"), eq("request-1"), eq("selection-1"), eq(3),
                eq(NOW), eq(NOW.plusSeconds(600)), any(), eq(Duration.ofMinutes(2))))
                .thenReturn(true);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(awaiting));

        ReturnedMessageOperationResult result = service.handle(request);

        assertThat(result.lifecycle().status()).isEqualTo(ReturnedMessageStatus.AWAITING_CONFIRMATION);
        verify(confirmationSender).send(any());
        verify(repository).markConfirmationSent(
                "tenant-1", "request-1", "confirmation-1");
    }

    @Test
    void unavailablePolicyIsReturnedAsRetryableFailureBeforePersistence() {
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateMessageSelection("tenant-1", "user-1", "period-1"))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.unavailable(
                        "POLICY_GLOBAL_POLICY_UNAVAILABLE"));

        assertThatThrownBy(() -> service.handle(request(1)))
                .isInstanceOf(ReturnedMessagePolicyUnavailableException.class);

        verify(repository, never()).create(any());
        verifyNoInteractions(messageQuery, confirmationSender);
    }

    @Test
    void expiredDetectedLifecycleIsNotQueriedDuringRecovery() {
        ReturnedMessageLifecycle detected = lifecycle(
                ReturnedMessageStatus.DETECTED, null, null, NOW.minusSeconds(1));
        ReturnedMessageLifecycle expired = lifecycle(
                ReturnedMessageStatus.EXPIRED, null, null, NOW.minusSeconds(1));
        when(repository.currentTime()).thenReturn(NOW);
        when(repository.expireTriggerIfDue("tenant-1", "request-1")).thenReturn(true);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(expired));

        ReturnedMessageOperationResult result = service.resumeDetection(detected);

        assertThat(result.lifecycle().status()).isEqualTo(ReturnedMessageStatus.EXPIRED);
        verifyNoInteractions(messageQuery, confirmationSender);
    }

    private ReturnedMessageRequest request(Integer connectionType) {
        return new ReturnedMessageRequest(
                "request-1",
                "trigger-1",
                "tenant-1",
                "user-1",
                "period-1",
                NOW.minus(Duration.ofMinutes(5)),
                NOW,
                NOW,
                NOW.plusSeconds(60),
                RoutingRef.forClientSession(
                        "tenant-1", "user-1", "target-1",
                        new RoutingScope("ABSENCE_PERIOD", "period-1"), connectionType));
    }

    private ReturnedMessageLifecycle lifecycle(
            ReturnedMessageStatus status,
            String confirmationId,
            String selectionRef) {
        return lifecycle(status, confirmationId, selectionRef, NOW.plusSeconds(60));
    }

    private ReturnedMessageLifecycle lifecycle(
            ReturnedMessageStatus status,
            String confirmationId,
            String selectionRef,
            Instant triggerExpiresAt) {
        return new ReturnedMessageLifecycle(
                "request-1",
                "trigger-1",
                "tenant-1",
                "user-1",
                "period-1",
                NOW.minus(Duration.ofMinutes(5)),
                NOW,
                triggerExpiresAt,
                RoutingRef.forClientSession(
                        "tenant-1", "user-1", "target-1",
                        new RoutingScope("ABSENCE_PERIOD", "period-1"), 1),
                status,
                selectionRef,
                selectionRef == null ? null : 3,
                selectionRef == null ? null : NOW,
                selectionRef == null ? null : NOW.plusSeconds(600),
                confirmationId,
                confirmationId == null ? null : ConfirmationDeliveryStatus.PENDING,
                confirmationId == null ? null : NOW.plusSeconds(120),
                null,
                null,
                null,
                null,
                null,
                NOW,
                NOW);
    }
}
