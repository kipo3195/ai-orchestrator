package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageAsyncTaskExecutor;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageResultSender;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;
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
                repository, resultSender, asyncTaskExecutor);
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
        ReturnedMessageLifecycle pending = lifecycle(
                ReturnedMessageStatus.DELIVERY_PENDING, result(), "delivery-1");
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

        verify(resultSender).send(new ReturnedMessageResultSender.ResultDelivery(
                "request-1",
                "execution-1",
                "delivery-1",
                result(),
                pending.routingRef()));
        verify(repository).deliveryCompleted(
                "tenant-1", "request-1", "execution-1", "delivery-1");
    }

    private ReturnedMessageLifecycle lifecycle(
            ReturnedMessageStatus status,
            ReturnedMessageTopicsResult result,
            String deliveryId) {
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
                NOW,
                NOW);
    }

    private ReturnedMessageTopicsResult result() {
        return new ReturnedMessageTopicsResult(
                List.of(new ReturnedMessageTopicsResult.Topic(
                        "제목 1",
                        "요약",
                        ReturnedMessageTopicsResult.Priority.NORMAL,
                        null,
                        1,
                        List.of("message-1"))),
                NOW);
    }
}
