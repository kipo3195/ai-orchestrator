package com.ucware.ai_orchestrator.returnedmessage.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;

@Service
public class ReturnedMessageConfirmationService implements HandleReturnedMessageConfirmation {

    private static final Logger logger = LoggerFactory.getLogger(ReturnedMessageConfirmationService.class);

    private final ReturnedMessageRepository repository;
    private final ReturnedMessageAccessEvaluator accessEvaluator;
    private final AiExecutionGateway executionGateway;
    private final HandleReturnedMessageExecutionResult executionResultHandler;

    public ReturnedMessageConfirmationService(
            ReturnedMessageRepository repository,
            ReturnedMessageAccessEvaluator accessEvaluator,
            AiExecutionGateway executionGateway,
            HandleReturnedMessageExecutionResult executionResultHandler) {
        this.repository = repository;
        this.accessEvaluator = accessEvaluator;
        this.executionGateway = executionGateway;
        this.executionResultHandler = executionResultHandler;
    }

    @Override
    public ReturnedMessageOperationResult handle(ReturnedMessageConfirmation confirmation) {
        ReturnedMessageLifecycle lifecycle = repository.findOwned(
                        confirmation.tenantId(), confirmation.userId(), confirmation.requestId())
                .orElseThrow(ReturnedMessageNotFoundException::new);
        validateCorrelation(lifecycle, confirmation);

        return switch (confirmation.decision()) {
            case NO -> decline(lifecycle, confirmation);
            case YES -> accept(lifecycle, confirmation);
        };
    }

    private ReturnedMessageOperationResult decline(
            ReturnedMessageLifecycle lifecycle,
            ReturnedMessageConfirmation confirmation) {
        boolean transitioned = repository.decline(
                confirmation.tenantId(),
                confirmation.userId(),
                confirmation.requestId(),
                confirmation.absencePeriodId(),
                confirmation.confirmationId(),
                confirmation.messageSelectionRef());
        if (!transitioned) {
            repository.expireIfDue(
                    confirmation.tenantId(), confirmation.userId(), confirmation.requestId());
        }
        return transitioned
                ? ReturnedMessageOperationResult.updated(load(lifecycle))
                : ReturnedMessageOperationResult.existing(load(lifecycle));
    }

    private ReturnedMessageOperationResult accept(
            ReturnedMessageLifecycle lifecycle,
            ReturnedMessageConfirmation confirmation) {
        if (lifecycle.status().terminal() || lifecycle.executionId() != null) {
            return ReturnedMessageOperationResult.existing(lifecycle);
        }

        if (lifecycle.confirmationExpiresAt() == null
                || !lifecycle.confirmationExpiresAt().isAfter(repository.currentTime())) {
            repository.expireIfDue(
                    confirmation.tenantId(), confirmation.userId(), confirmation.requestId());
            return ReturnedMessageOperationResult.existing(load(lifecycle));
        }

        var access = accessEvaluator.evaluateAiContext(
                lifecycle.tenantId(),
                lifecycle.userId(),
                lifecycle.absencePeriodId(),
                lifecycle.messageSelectionRef());
        if (access.unavailable()) {
            throw new ReturnedMessagePolicyUnavailableException(access.reason());
        }
        if (access.denied()) {
            boolean blocked = repository.blockByPolicy(
                    lifecycle.tenantId(),
                    lifecycle.userId(),
                    lifecycle.requestId(),
                    lifecycle.confirmationId(),
                    access.reason());
            return blocked
                    ? ReturnedMessageOperationResult.updated(load(lifecycle))
                    : ReturnedMessageOperationResult.existing(load(lifecycle));
        }

        String executionId = UUID.randomUUID().toString();
        boolean transitioned = repository.startProcessing(
                lifecycle.tenantId(),
                lifecycle.userId(),
                lifecycle.requestId(),
                lifecycle.confirmationId(),
                executionId);
        if (!transitioned) {
            return ReturnedMessageOperationResult.existing(load(lifecycle));
        }

        ReturnedMessageLifecycle processing = load(lifecycle);
        dispatch(processing);
        return ReturnedMessageOperationResult.updated(load(lifecycle));
    }

    void redispatch(ReturnedMessageLifecycle lifecycle) {
        dispatch(lifecycle);
    }

    private void dispatch(ReturnedMessageLifecycle lifecycle) {
        AiExecutionGateway.ExecutionResult dispatchResult;
        try {
            dispatchResult = executionGateway.start(command(lifecycle));
        } catch (RuntimeException exception) {
            logger.warn("Returned Message AI dispatch is uncertain. requestId={}, executionId={}",
                    lifecycle.requestId(), lifecycle.executionId(), exception);
            return;
        }

        if (dispatchResult.status() == AiExecutionGateway.Status.SUCCEEDED
                || dispatchResult.status() == AiExecutionGateway.Status.FAILED
                || dispatchResult.status() == AiExecutionGateway.Status.CANCELLED) {
            executionResultHandler.handle(
                    lifecycle.tenantId(),
                    lifecycle.userId(),
                    lifecycle.requestId(),
                    lifecycle.executionId(),
                    dispatchResult);
        }
    }

    AiExecutionGateway.ExecutionCommand command(ReturnedMessageLifecycle lifecycle) {
        return new AiExecutionGateway.ExecutionCommand(
                lifecycle.requestId(),
                lifecycle.triggerId(),
                lifecycle.executionId(),
                lifecycle.tenantId(),
                lifecycle.userId(),
                lifecycle.absencePeriodId(),
                lifecycle.messageSelectionRef(),
                lifecycle.routingRef());
    }

    private void validateCorrelation(
            ReturnedMessageLifecycle lifecycle,
            ReturnedMessageConfirmation confirmation) {
        boolean matches = lifecycle.absencePeriodId().equals(confirmation.absencePeriodId())
                && confirmation.confirmationId().equals(lifecycle.confirmationId())
                && confirmation.messageSelectionRef().equals(lifecycle.messageSelectionRef())
                && confirmation.targetRef().equals(lifecycle.routingRef().clientSessionId())
                && lifecycle.routingRef().connectionType() != null
                && confirmation.connectionType() == lifecycle.routingRef().connectionType();
        if (!matches) {
            throw new ReturnedMessageNotFoundException();
        }
    }

    private ReturnedMessageLifecycle load(ReturnedMessageLifecycle lifecycle) {
        return repository.findOwned(
                        lifecycle.tenantId(), lifecycle.userId(), lifecycle.requestId())
                .orElseThrow(ReturnedMessageNotFoundException::new);
    }
}
