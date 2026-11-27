package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ucware.ai_orchestrator.result.domain.LifecycleEvent;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageAsyncTaskExecutor;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository.DeliveryRetryResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageResultSender;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageProperties;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

class ReturnedMessageExecutionResultServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");

    private ReturnedMessageRepository repository;
    private ReturnedMessageResultSender resultSender;
    private ReturnedMessageAsyncTaskExecutor asyncTaskExecutor;
    private ReturnedMessageExecutionResultService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReturnedMessageRepository.class);
        resultSender = mock(ReturnedMessageResultSender.class);
        asyncTaskExecutor = mock(ReturnedMessageAsyncTaskExecutor.class);
        service = new ReturnedMessageExecutionResultService(
                repository, resultSender, asyncTaskExecutor, properties(1));
    }

    @Test
    void successfulExecutionPersistsResultBeforeSchedulingDelivery() {
        ReturnedMessageLifecycle processing = lifecycle(
                ReturnedMessageStatus.PROCESSING, null, null);
        ReturnedMessageLifecycle pending = lifecycle(
                ReturnedMessageStatus.DELIVERY_PENDING, result(), "delivery-1");
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(processing), Optional.of(pending));
        when(repository.executionSucceeded(
                eq("tenant-1"), eq("request-1"), eq("execution-1"),
                eq(result()), anyString())).thenReturn(true);
        when(asyncTaskExecutor.submitDelivery(any())).thenReturn(true);

        ReturnedMessageOperationResult operation = service.handle(
                "tenant-1",
                "user-1",
                "request-1",
                "execution-1",
                new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.SUCCEEDED, result(), null));

        assertThat(operation.lifecycle().status())
                .isEqualTo(ReturnedMessageStatus.DELIVERY_PENDING);
        assertThat(operation.lifecycle().result()).isEqualTo(result());
        verify(asyncTaskExecutor).submitDelivery(any());
        verify(resultSender, never()).send(any());
    }

    @Test
    void deliveryWorkerSendsPersistedPayloadAndCompletesLifecycle() {
        ReturnedMessageLifecycle processing = lifecycle(
                ReturnedMessageStatus.PROCESSING, null, null);
        LifecycleEvent topicsReady = new LifecycleEvent(
                "returned_message_topic",
                "request-1",
                "client-event-3",
                3,
                "TOPICS_READY",
                NOW.plusSeconds(2));
        ReturnedMessageLifecycle pending = lifecycle(
                ReturnedMessageStatus.DELIVERY_PENDING, result(), "delivery-1", topicsReady);
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(processing), Optional.of(pending));
        when(repository.executionSucceeded(
                eq("tenant-1"), eq("request-1"), eq("execution-1"),
                eq(result()), anyString())).thenReturn(true);
        when(resultSender.send(any()))
                .thenReturn(ReturnedMessageResultSender.DeliveryOutcome.DELIVERED);
        when(asyncTaskExecutor.submitDelivery(any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });

        service.handle(
                "tenant-1",
                "user-1",
                "request-1",
                "execution-1",
                new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.SUCCEEDED, result(), null));

        ArgumentCaptor<ReturnedMessageResultSender.ResultDelivery> deliveryCaptor =
                ArgumentCaptor.forClass(ReturnedMessageResultSender.ResultDelivery.class);
        verify(resultSender).send(deliveryCaptor.capture());
        assertThat(deliveryCaptor.getValue().requestId()).isEqualTo("request-1");
        assertThat(deliveryCaptor.getValue().executionId()).isEqualTo("execution-1");
        assertThat(deliveryCaptor.getValue().deliveryId()).isEqualTo("delivery-1");
        assertThat(deliveryCaptor.getValue().result()).isEqualTo(result());
        assertThat(deliveryCaptor.getValue().lifecycleEvent()).isEqualTo(topicsReady);
        assertThat(deliveryCaptor.getValue().routingRef()).isEqualTo(pending.routingRef());
        verify(repository).deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "delivery-1");
    }

    @Test
    void retryableDeliveryFailureSchedulesConfiguredRetry() {
        ReturnedMessageLifecycle processing = lifecycle(
                ReturnedMessageStatus.PROCESSING, null, null);
        ReturnedMessageLifecycle pending = lifecycle(
                ReturnedMessageStatus.DELIVERY_PENDING, result(), "delivery-1");
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(processing), Optional.of(pending));
        when(repository.executionSucceeded(
                eq("tenant-1"), eq("request-1"), eq("execution-1"),
                eq(result()), anyString())).thenReturn(true);
        when(resultSender.send(any()))
                .thenReturn(ReturnedMessageResultSender.DeliveryOutcome.RETRYABLE_FAILURE);
        when(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 1,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .thenReturn(DeliveryRetryResult.RETRY_SCHEDULED);
        when(asyncTaskExecutor.submitDelivery(any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });

        service.handle(
                "tenant-1",
                "user-1",
                "request-1",
                "execution-1",
                new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.SUCCEEDED, result(), null));

        verify(repository).recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 1,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED");
    }

    @Test
    void zeroConfiguredRetriesArePassedToFailureTransition() {
        service = new ReturnedMessageExecutionResultService(
                repository, resultSender, asyncTaskExecutor, properties(0));
        ReturnedMessageLifecycle processing = lifecycle(
                ReturnedMessageStatus.PROCESSING, null, null);
        ReturnedMessageLifecycle pending = lifecycle(
                ReturnedMessageStatus.DELIVERY_PENDING, result(), "delivery-1");
        when(repository.findOwned("tenant-1", "user-1", "request-1"))
                .thenReturn(Optional.of(processing), Optional.of(pending));
        when(repository.executionSucceeded(
                eq("tenant-1"), eq("request-1"), eq("execution-1"),
                eq(result()), anyString())).thenReturn(true);
        when(resultSender.send(any()))
                .thenReturn(ReturnedMessageResultSender.DeliveryOutcome.RETRYABLE_FAILURE);
        when(repository.recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 0,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED"))
                .thenReturn(DeliveryRetryResult.RETRIES_EXHAUSTED);
        when(asyncTaskExecutor.submitDelivery(any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });

        service.handle(
                "tenant-1",
                "user-1",
                "request-1",
                "execution-1",
                new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.SUCCEEDED, result(), null));

        verify(repository).recordRetryableDeliveryFailure(
                "tenant-1", "request-1", "execution-1", "delivery-1", 0,
                "RESULT_DELIVERY_RETRIES_EXHAUSTED");
    }

    private ReturnedMessageProperties properties(int deliveryMaxRetries) {
        return new ReturnedMessageProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofDays(7),
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                100,
                deliveryMaxRetries,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                100);
    }

    private ReturnedMessageLifecycle lifecycle(
            ReturnedMessageStatus status,
            ReturnedMessageTopicsResult result,
            String deliveryId) {
        return lifecycle(status, result, deliveryId, null);
    }

    private ReturnedMessageLifecycle lifecycle(
            ReturnedMessageStatus status,
            ReturnedMessageTopicsResult result,
            String deliveryId,
            LifecycleEvent lifecycleEvent) {
        return new ReturnedMessageLifecycle(
                "request-1",
                "trigger-1",
                "tenant-1",
                "user-1",
                "period-1",
                NOW.minusSeconds(300),
                NOW,
                NOW.plusSeconds(60),
                RoutingRef.forClientSession(
                        "tenant-1",
                        "user-1",
                        "target-1",
                        new RoutingScope("ABSENCE_PERIOD", "period-1"),
                        1),
                status,
                "selection-1",
                1,
                NOW,
                NOW.plusSeconds(600),
                "confirmation-1",
                NOW.plusSeconds(120),
                "execution-1",
                result,
                deliveryId,
                null,
                null,
                lifecycleEvent,
                NOW,
                NOW);
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
                NOW);
    }
}
