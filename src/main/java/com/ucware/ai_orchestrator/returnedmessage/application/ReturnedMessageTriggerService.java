package com.ucware.ai_orchestrator.returnedmessage.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.returnedmessage.application.port.LifecycleCreationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageConfirmationSender;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery.MessageSelection;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery.MessageSelectionQuery;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageProperties;

@Service
public class ReturnedMessageTriggerService implements HandleUserReturned {

    private static final Logger logger = LoggerFactory.getLogger(ReturnedMessageTriggerService.class);

    private final ReturnedMessageRepository repository;
    private final ReturnedMessageQuery messageQuery;
    private final ReturnedMessageConfirmationSender confirmationSender;
    private final ReturnedMessageAccessEvaluator accessEvaluator;
    private final ReturnedMessageProperties properties;

    public ReturnedMessageTriggerService(
            ReturnedMessageRepository repository,
            ReturnedMessageQuery messageQuery,
            ReturnedMessageConfirmationSender confirmationSender,
            ReturnedMessageAccessEvaluator accessEvaluator,
            ReturnedMessageProperties properties) {
        this.repository = repository;
        this.messageQuery = messageQuery;
        this.confirmationSender = confirmationSender;
        this.accessEvaluator = accessEvaluator;
        this.properties = properties;
    }

    @Override
    public ReturnedMessageOperationResult handle(ReturnedMessageRequest request) {
        validateSourceContract(request);

        String rejection = validateEligibility(request);
        if (rejection != null) {
            return ReturnedMessageOperationResult.rejected(rejection);
        }

        var access = accessEvaluator.evaluateMessageSelection(
                request.tenantId(), request.userId(), request.absencePeriodId());
        if (access.unavailable()) {
            throw new ReturnedMessagePolicyUnavailableException(access.reason());
        }
        if (access.denied()) {
            return ReturnedMessageOperationResult.rejected(access.reason());
        }

        LifecycleCreationResult creation = repository.create(request);
        if (creation.outcome() == LifecycleCreationResult.Outcome.CONFLICT) {
            throw new ReturnedMessageContractConflictException(
                    "triggerId or requestId is already associated with another correlation");
        }
        if (creation.outcome() == LifecycleCreationResult.Outcome.ALREADY_EXISTS) {
            return ReturnedMessageOperationResult.existing(creation.lifecycle());
        }

        return selectMessages(creation.lifecycle(), true);
    }

    ReturnedMessageOperationResult resumeDetection(ReturnedMessageLifecycle lifecycle) {
        if (!lifecycle.triggerExpiresAt().isAfter(repository.currentTime())) {
            repository.expireTriggerIfDue(lifecycle.tenantId(), lifecycle.requestId());
            return ReturnedMessageOperationResult.updated(load(lifecycle));
        }
        return selectMessages(lifecycle, false);
    }

    private ReturnedMessageOperationResult selectMessages(
            ReturnedMessageLifecycle lifecycle,
            boolean created) {
        MessageSelection selection;
        try {
            Instant lookbackSince = laterOf(
                    lifecycle.offlineSince(),
                    lifecycle.returnedAt().minus(properties.maxLookback()));
            selection = messageQuery.selectUnreadMessages(new MessageSelectionQuery(
                    lifecycle.requestId(),
                    lifecycle.tenantId(),
                    lifecycle.userId(),
                    lifecycle.absencePeriodId(),
                    lifecycle.offlineSince(),
                    lookbackSince,
                    lifecycle.returnedAt()));
        } catch (RuntimeException exception) {
            repository.failDetection(
                    lifecycle.tenantId(), lifecycle.requestId(), "MESSAGE_QUERY_FAILED");
            logger.warn("Returned Message selection failed. requestId={}",
                    lifecycle.requestId(), exception);
            return resultAfterTransition(lifecycle, created);
        }

        if (!validSelection(lifecycle, selection)) {
            repository.failDetection(
                    lifecycle.tenantId(), lifecycle.requestId(), "INVALID_MESSAGE_SELECTION");
            return resultAfterTransition(lifecycle, created);
        }

        if (selection.unreadCount() == 0) {
            repository.markNoMessages(lifecycle.tenantId(), lifecycle.requestId());
            return resultAfterTransition(lifecycle, created);
        }

        Instant now = repository.currentTime();
        if (!selection.selectionExpiresAt().isAfter(now)) {
            repository.failDetection(
                    lifecycle.tenantId(), lifecycle.requestId(), "MESSAGE_SELECTION_EXPIRED");
            return resultAfterTransition(lifecycle, created);
        }

        String confirmationId = UUID.randomUUID().toString();
        boolean transitioned = repository.awaitConfirmation(
                lifecycle.tenantId(),
                lifecycle.requestId(),
                selection.messageSelectionRef(),
                selection.unreadCount(),
                selection.selectedAt(),
                selection.selectionExpiresAt(),
                confirmationId,
                properties.confirmationTimeout());
        if (!transitioned) {
            return resultAfterTransition(lifecycle, created);
        }

        ReturnedMessageLifecycle awaiting = load(lifecycle);
        sendConfirmation(awaiting);
        return created
                ? ReturnedMessageOperationResult.created(load(lifecycle))
                : ReturnedMessageOperationResult.updated(load(lifecycle));
    }

    void resendConfirmation(ReturnedMessageLifecycle lifecycle) {
        sendConfirmation(lifecycle);
    }

    private void sendConfirmation(ReturnedMessageLifecycle lifecycle) {
        try {
            confirmationSender.send(new ReturnedMessageConfirmationSender.ConfirmationPrompt(
                    lifecycle.requestId(),
                    lifecycle.absencePeriodId(),
                    lifecycle.confirmationId(),
                    lifecycle.messageSelectionRef(),
                    lifecycle.unreadCount(),
                    lifecycle.confirmationExpiresAt(),
                    lifecycle.routingRef()));
            repository.markConfirmationSent(
                    lifecycle.tenantId(), lifecycle.requestId(), lifecycle.confirmationId());
        } catch (RuntimeException exception) {
            logger.warn("Returned Message confirmation delivery remains pending. requestId={}",
                    lifecycle.requestId(), exception);
        }
    }

    private String validateEligibility(ReturnedMessageRequest request) {
        if (!properties.enabled()) {
            return "FEATURE_DISABLED";
        }
        Instant databaseNow = repository.currentTime();
        if (!request.triggerExpiresAt().isAfter(databaseNow)) {
            return "TRIGGER_EXPIRED";
        }
        Duration absence = Duration.between(request.offlineSince(), request.returnedAt());
        if (absence.compareTo(properties.minAwayDuration()) < 0) {
            return "AWAY_DURATION_TOO_SHORT";
        }
        if (absence.compareTo(properties.maxAbsenceDuration()) > 0) {
            return "ABSENCE_TOO_LONG";
        }
        return null;
    }

    private void validateSourceContract(ReturnedMessageRequest request) {
        if (request.routingRef().connectionType() == null
                || request.routingRef().connectionType() <= 0) {
            throw new IllegalArgumentException("Invalid Returned Message source connection contract");
        }
        if (!request.offlineSince().isBefore(request.returnedAt())
                || request.returnedAt().isAfter(request.triggerExpiresAt())) {
            throw new IllegalArgumentException("Invalid Returned Message source time contract");
        }
    }

    private boolean validSelection(ReturnedMessageLifecycle lifecycle, MessageSelection selection) {
        if (selection == null
                || !lifecycle.absencePeriodId().equals(selection.absencePeriodId())
                || selection.unreadCount() < 0) {
            return false;
        }
        if (selection.unreadCount() == 0) {
            return true;
        }
        return hasText(selection.messageSelectionRef())
                && selection.selectedAt() != null
                && selection.selectionExpiresAt() != null
                && !selection.selectedAt().isAfter(selection.selectionExpiresAt());
    }

    private ReturnedMessageOperationResult resultAfterTransition(
            ReturnedMessageLifecycle original,
            boolean created) {
        ReturnedMessageLifecycle current = load(original);
        return created
                ? ReturnedMessageOperationResult.created(current)
                : ReturnedMessageOperationResult.updated(current);
    }

    private ReturnedMessageLifecycle load(ReturnedMessageLifecycle lifecycle) {
        return repository.findOwned(
                        lifecycle.tenantId(), lifecycle.userId(), lifecycle.requestId())
                .orElseThrow(ReturnedMessageNotFoundException::new);
    }

    private static Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
