package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.result.domain.RoutingRef;
import com.ucware.ai_orchestrator.result.domain.RoutingScope;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ConfirmationDeliveryStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageDecision;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;

class ReturnedMessageConfirmationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");

    private ReturnedMessageRepository repository;
    private ReturnedMessageAccessEvaluator accessEvaluator;
    private AiExecutionGateway executionGateway;
    private HandleReturnedMessageExecutionResult resultHandler;
    private ReturnedMessageConfirmationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReturnedMessageRepository.class);
        accessEvaluator = mock(ReturnedMessageAccessEvaluator.class);
        executionGateway = mock(AiExecutionGateway.class);
        resultHandler = mock(HandleReturnedMessageExecutionResult.class);
        service = new ReturnedMessageConfirmationService(
                repository, accessEvaluator, executionGateway, resultHandler);
    }

    @Test
    void yesStartsAiOnlyAfterConditionalProcessingTransitionWins() {
        ReturnedMessageLifecycle awaiting = lifecycle(ReturnedMessageStatus.AWAITING_CONFIRMATION, null);
        ReturnedMessageLifecycle processing = lifecycle(ReturnedMessageStatus.PROCESSING, "execution-1");
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(awaiting), Optional.of(processing), Optional.of(processing));
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateAiContext("tenant-1", "user-1", "period-1", "selection-1"))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.allow());
        when(repository.startProcessing(
                org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("request-1"),
                org.mockito.ArgumentMatchers.eq("confirmation-1"),
                any())).thenReturn(true);
        when(executionGateway.start(any())).thenReturn(AiExecutionGateway.ExecutionResult.accepted());

        ReturnedMessageOperationResult result = service.handle(confirmation(ReturnedMessageDecision.YES));

        assertThat(result.outcome()).isEqualTo(ReturnedMessageOperationResult.Outcome.UPDATED);
        verify(executionGateway).start(any());
    }

    @Test
    void lostYesRaceDoesNotStartAi() {
        ReturnedMessageLifecycle awaiting = lifecycle(ReturnedMessageStatus.AWAITING_CONFIRMATION, null);
        ReturnedMessageLifecycle declined = lifecycle(ReturnedMessageStatus.DECLINED, null);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(awaiting), Optional.of(declined));
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateAiContext("tenant-1", "user-1", "period-1", "selection-1"))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.allow());
        when(repository.startProcessing(
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        ReturnedMessageOperationResult result = service.handle(confirmation(ReturnedMessageDecision.YES));

        assertThat(result.outcome()).isEqualTo(ReturnedMessageOperationResult.Outcome.EXISTING);
        assertThat(result.lifecycle().status()).isEqualTo(ReturnedMessageStatus.DECLINED);
        verify(executionGateway, never()).start(any());
    }

    @Test
    void policyRejectionBlocksWithoutStartingAi() {
        ReturnedMessageLifecycle awaiting = lifecycle(ReturnedMessageStatus.AWAITING_CONFIRMATION, null);
        ReturnedMessageLifecycle blocked = lifecycle(ReturnedMessageStatus.BLOCKED_BY_POLICY, null);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(awaiting), Optional.of(blocked));
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateAiContext("tenant-1", "user-1", "period-1", "selection-1"))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.denied(
                        "PERMISSION_DENIED"));
        when(repository.blockByPolicy(
                "tenant-1", "user-1", "request-1", "confirmation-1",
                "PERMISSION_DENIED")).thenReturn(true);

        ReturnedMessageOperationResult result = service.handle(confirmation(ReturnedMessageDecision.YES));

        assertThat(result.lifecycle().status()).isEqualTo(ReturnedMessageStatus.BLOCKED_BY_POLICY);
        verify(executionGateway, never()).start(any());
    }

    @Test
    void unavailablePolicyLeavesConfirmationAwaitingForRetry() {
        ReturnedMessageLifecycle awaiting = lifecycle(ReturnedMessageStatus.AWAITING_CONFIRMATION, null);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(awaiting));
        when(repository.currentTime()).thenReturn(NOW);
        when(accessEvaluator.evaluateAiContext("tenant-1", "user-1", "period-1", "selection-1"))
                .thenReturn(ReturnedMessageAccessEvaluator.AccessDecision.unavailable(
                        "PERMISSION_UNAVAILABLE"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.handle(confirmation(ReturnedMessageDecision.YES)))
                .isInstanceOf(ReturnedMessagePolicyUnavailableException.class);

        verify(repository, never()).blockByPolicy(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).startProcessing(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(executionGateway, never()).start(any());
    }

    @Test
    void noNeverStartsAi() {
        ReturnedMessageLifecycle awaiting = lifecycle(ReturnedMessageStatus.AWAITING_CONFIRMATION, null);
        ReturnedMessageLifecycle declined = lifecycle(ReturnedMessageStatus.DECLINED, null);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(awaiting), Optional.of(declined));
        when(repository.decline(
                "tenant-1", "user-1", "request-1", "period-1",
                "confirmation-1", "selection-1")).thenReturn(true);

        ReturnedMessageOperationResult result = service.handle(confirmation(ReturnedMessageDecision.NO));

        assertThat(result.lifecycle().status()).isEqualTo(ReturnedMessageStatus.DECLINED);
        verify(executionGateway, never()).start(any());
    }

    private ReturnedMessageConfirmation confirmation(ReturnedMessageDecision decision) {
        return new ReturnedMessageConfirmation(
                "tenant-1",
                "user-1",
                "request-1",
                "period-1",
                "confirmation-1",
                "selection-1",
                decision,
                NOW,
                "target-1",
                1);
    }

    private ReturnedMessageLifecycle lifecycle(ReturnedMessageStatus status, String executionId) {
        return new ReturnedMessageLifecycle(
                "request-1",
                "trigger-1",
                "tenant-1",
                "user-1",
                "period-1",
                NOW.minus(Duration.ofMinutes(5)),
                NOW,
                NOW.plusSeconds(60),
                RoutingRef.forClientSession(
                        "tenant-1", "user-1", "target-1",
                        new RoutingScope("ABSENCE_PERIOD", "period-1"), 1),
                status,
                "selection-1",
                3,
                NOW,
                NOW.plusSeconds(600),
                "confirmation-1",
                ConfirmationDeliveryStatus.SENT,
                NOW.plusSeconds(120),
                executionId,
                null,
                null,
                null,
                null,
                NOW,
                NOW);
    }
}
