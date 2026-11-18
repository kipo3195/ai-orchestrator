package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageProperties;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

class ReturnedMessageMaintenanceServiceTests {

    @Test
    void notFoundDuringReconciliationDoesNotStartAnotherExecution() {
        ReturnedMessageRepository repository = mock(ReturnedMessageRepository.class);
        ReturnedMessageTriggerService triggerService = mock(ReturnedMessageTriggerService.class);
        ReturnedMessageExecutionResultService resultService = mock(
                ReturnedMessageExecutionResultService.class);
        AiExecutionGateway executionGateway = mock(AiExecutionGateway.class);
        ReturnedMessageLifecycle lifecycle = processingLifecycle();
        when(repository.findRecoverable(10)).thenReturn(List.of(lifecycle));
        when(repository.tryClaim(
                eq(lifecycle.tenantId()), eq(lifecycle.requestId()),
                anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);
        when(executionGateway.reconcile("tenant-1", "execution-1"))
                .thenReturn(new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.NOT_FOUND, null, null));
        ReturnedMessageMaintenanceService service = new ReturnedMessageMaintenanceService(
                repository,
                triggerService,
                resultService,
                executionGateway,
                properties());

        service.recoverNonTerminalLifecycles();

        verify(executionGateway).reconcile("tenant-1", "execution-1");
        verify(executionGateway, never()).start(
                org.mockito.ArgumentMatchers.any());
        verify(resultService, never()).handle(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private ReturnedMessageProperties properties() {
        return new ReturnedMessageProperties(
                true,
                Duration.ofMinutes(1),
                Duration.ofDays(1),
                Duration.ofDays(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                10,
                1,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                10);
    }

    private ReturnedMessageLifecycle processingLifecycle() {
        Instant now = Instant.parse("2026-11-17T01:00:00Z");
        return new ReturnedMessageLifecycle(
                "request-1",
                "trigger-1",
                "tenant-1",
                "user-1",
                "period-1",
                now.minusSeconds(300),
                now,
                now.plusSeconds(60),
                RoutingRef.forClientSession(
                        "tenant-1",
                        "user-1",
                        "target-1",
                        new RoutingScope("ABSENCE_PERIOD", "period-1"),
                        1),
                ReturnedMessageStatus.PROCESSING,
                "selection-1",
                1,
                now,
                now.plusSeconds(600),
                "confirmation-1",
                now.plusSeconds(120),
                "execution-1",
                null,
                null,
                null,
                null,
                now,
                now);
    }
}
