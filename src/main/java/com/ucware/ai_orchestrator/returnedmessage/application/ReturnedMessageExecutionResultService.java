package com.ucware.ai_orchestrator.returnedmessage.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageAsyncTaskExecutor;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageResultSender;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageFailureStage;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;

@Service
public class ReturnedMessageExecutionResultService
        implements HandleReturnedMessageExecutionResult {

    private static final Logger logger = LoggerFactory.getLogger(
            ReturnedMessageExecutionResultService.class);

    private final ReturnedMessageRepository repository;
    private final ReturnedMessageResultSender resultSender;
    private final ReturnedMessageAsyncTaskExecutor asyncTaskExecutor;

    public ReturnedMessageExecutionResultService(
            ReturnedMessageRepository repository,
            ReturnedMessageResultSender resultSender,
            ReturnedMessageAsyncTaskExecutor asyncTaskExecutor) {
        this.repository = repository;
        this.resultSender = resultSender;
        this.asyncTaskExecutor = asyncTaskExecutor;
    }

    @Override
    public ReturnedMessageOperationResult handle(
            String tenantId,
            String userId,
            String requestId,
            String executionId,
            AiExecutionGateway.ExecutionResult result) {
        ReturnedMessageLifecycle lifecycle = repository.findOwned(tenantId, userId, requestId)
                .orElseThrow(ReturnedMessageNotFoundException::new);
        if (!executionId.equals(lifecycle.executionId())) {
            return ReturnedMessageOperationResult.existing(lifecycle);
        }

        return switch (result.status()) {
            case ACCEPTED, RUNNING, UNKNOWN, NOT_FOUND ->
                    ReturnedMessageOperationResult.existing(lifecycle);
            case SUCCEEDED -> succeeded(lifecycle, result);
            case FAILED -> failed(lifecycle, result);
            case CANCELLED -> cancelled(lifecycle);
        };
    }

    void retryDelivery(ReturnedMessageLifecycle lifecycle) {
        deliver(lifecycle);
    }

    private ReturnedMessageOperationResult succeeded(
            ReturnedMessageLifecycle lifecycle,
            AiExecutionGateway.ExecutionResult result) {
        if (result.result() == null) {
            return failed(lifecycle, new AiExecutionGateway.ExecutionResult(
                    AiExecutionGateway.Status.FAILED, null, "AI_RESULT_MISSING"));
        }

        String deliveryId = UUID.randomUUID().toString();
        boolean transitioned = repository.executionSucceeded(
                lifecycle.tenantId(),
                lifecycle.requestId(),
                lifecycle.executionId(),
                result.result(),
                deliveryId);
        if (!transitioned) {
            return ReturnedMessageOperationResult.existing(load(lifecycle));
        }

        ReturnedMessageLifecycle pending = load(lifecycle);
        if (!asyncTaskExecutor.submitDelivery(() -> deliver(pending))) {
            logger.warn("Returned Message result delivery remains pending. requestId={}, executionId={}",
                    pending.requestId(), pending.executionId());
        }
        return ReturnedMessageOperationResult.updated(pending);
    }

    private ReturnedMessageOperationResult failed(
            ReturnedMessageLifecycle lifecycle,
            AiExecutionGateway.ExecutionResult result) {
        boolean transitioned = repository.executionFailed(
                lifecycle.tenantId(),
                lifecycle.requestId(),
                lifecycle.executionId(),
                ReturnedMessageFailureStage.AI_EXECUTION,
                safeReason(result.safeFailureReason(), "AI_EXECUTION_FAILED"));
        return transitioned
                ? ReturnedMessageOperationResult.updated(load(lifecycle))
                : ReturnedMessageOperationResult.existing(load(lifecycle));
    }

    private ReturnedMessageOperationResult cancelled(ReturnedMessageLifecycle lifecycle) {
        boolean transitioned = repository.executionCancelled(
                lifecycle.tenantId(), lifecycle.requestId(), lifecycle.executionId());
        return transitioned
                ? ReturnedMessageOperationResult.updated(load(lifecycle))
                : ReturnedMessageOperationResult.existing(load(lifecycle));
    }

    private void deliver(ReturnedMessageLifecycle lifecycle) {
        if (lifecycle.result() == null) {
            repository.deliveryFailed(
                    lifecycle.tenantId(), lifecycle.requestId(),
                    lifecycle.executionId(), lifecycle.deliveryId(),
                    "RESULT_PAYLOAD_MISSING");
            return;
        }
        try {
            ReturnedMessageResultSender.DeliveryOutcome outcome = resultSender.send(
                    new ReturnedMessageResultSender.ResultDelivery(
                            lifecycle.requestId(),
                            lifecycle.executionId(),
                            lifecycle.deliveryId(),
                            lifecycle.result(),
                            lifecycle.routingRef()));
            if (outcome == ReturnedMessageResultSender.DeliveryOutcome.DELIVERED) {
                repository.deliveryCompleted(
                        lifecycle.tenantId(), lifecycle.requestId(),
                        lifecycle.executionId(), lifecycle.deliveryId());
            } else if (outcome == ReturnedMessageResultSender.DeliveryOutcome.PERMANENT_FAILURE) {
                repository.deliveryFailed(
                        lifecycle.tenantId(), lifecycle.requestId(),
                        lifecycle.executionId(), lifecycle.deliveryId(),
                        "RESULT_DELIVERY_FAILED");
            }
        } catch (RuntimeException exception) {
            logger.warn("Returned Message result delivery remains pending. requestId={}, executionId={}",
                    lifecycle.requestId(), lifecycle.executionId(), exception);
        }
    }

    private ReturnedMessageLifecycle load(ReturnedMessageLifecycle lifecycle) {
        return repository.findOwned(
                        lifecycle.tenantId(), lifecycle.userId(), lifecycle.requestId())
                .orElseThrow(ReturnedMessageNotFoundException::new);
    }

    private static String safeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }
}
