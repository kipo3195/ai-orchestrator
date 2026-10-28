package com.ucware.ai_orchestrator.returnedmessage.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageAsyncTaskExecutor;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;

@Service
public class ReturnedMessageConfirmationService implements HandleReturnedMessageConfirmation {

    private static final Logger logger = LoggerFactory.getLogger(ReturnedMessageConfirmationService.class);

    private final ReturnedMessageRepository repository;
    private final ReturnedMessageSelectionRepository selectionRepository;
    private final ReturnedMessageAccessEvaluator accessEvaluator;
    private final AiExecutionGateway executionGateway;
    private final HandleReturnedMessageExecutionResult executionResultHandler;
    private final ReturnedMessageAsyncTaskExecutor asyncTaskExecutor;

    public ReturnedMessageConfirmationService(
            ReturnedMessageRepository repository,
            ReturnedMessageSelectionRepository selectionRepository,
            ReturnedMessageAccessEvaluator accessEvaluator,
            AiExecutionGateway executionGateway,
            HandleReturnedMessageExecutionResult executionResultHandler,
            ReturnedMessageAsyncTaskExecutor asyncTaskExecutor) {
        this.repository = repository;
        this.selectionRepository = selectionRepository;
        this.accessEvaluator = accessEvaluator;
        this.executionGateway = executionGateway;
        this.executionResultHandler = executionResultHandler;
        this.asyncTaskExecutor = asyncTaskExecutor;
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

        ReturnedMessageSelection selection = loadSelection(lifecycle, true);
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
        if (!asyncTaskExecutor.submitExecution(() -> dispatch(processing, selection))) {
            logger.warn("Returned Message AI execution remains pending. requestId={}, executionId={}",
                    processing.requestId(), processing.executionId());
        }
        return ReturnedMessageOperationResult.updated(processing);
    }

    void redispatch(ReturnedMessageLifecycle lifecycle) {
        dispatch(lifecycle, loadSelection(lifecycle, false));
    }

    private void dispatch(
            ReturnedMessageLifecycle lifecycle,
            ReturnedMessageSelection selection) {
        AiExecutionGateway.ExecutionResult dispatchResult;
        try {
            dispatchResult = executionGateway.start(command(lifecycle, selection));
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

    AiExecutionGateway.ExecutionCommand command(
            ReturnedMessageLifecycle lifecycle,
            ReturnedMessageSelection selection) {
        return new AiExecutionGateway.ExecutionCommand(
                lifecycle.requestId(),
                lifecycle.triggerId(),
                lifecycle.executionId(),
                lifecycle.tenantId(),
                lifecycle.userId(),
                lifecycle.absencePeriodId(),
                lifecycle.messageSelectionRef(),
                selection.items(),
                lifecycle.routingRef());
    }

    private ReturnedMessageSelection loadSelection(
            ReturnedMessageLifecycle lifecycle,
            boolean activeOnly) {
        var selection = activeOnly
                ? selectionRepository.findOwnedActive(
                        lifecycle.messageSelectionRef(),
                        lifecycle.requestId(),
                        lifecycle.tenantId(),
                        lifecycle.userId(),
                        lifecycle.absencePeriodId())
                : selectionRepository.findOwned(
                        lifecycle.messageSelectionRef(),
                        lifecycle.requestId(),
                        lifecycle.tenantId(),
                        lifecycle.userId(),
                        lifecycle.absencePeriodId());
        ReturnedMessageSelection found = selection.orElseThrow(() ->
                new ReturnedMessagePersistenceException(
                        "Returned Message selection is unavailable", null));
        if (found.items().size() != lifecycle.unreadCount()) {
            throw new ReturnedMessagePersistenceException(
                    "Returned Message selection item count does not match lifecycle", null);
        }
        return found;
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
