package com.ucware.ai_orchestrator.returnedmessage.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageRepository;
import com.ucware.ai_orchestrator.returnedmessage.domain.ConfirmationDeliveryStatus;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageProperties;

@Service
public class ReturnedMessageMaintenanceService {

    private static final Logger logger = LoggerFactory.getLogger(
            ReturnedMessageMaintenanceService.class);

    private final ReturnedMessageRepository repository;
    private final ReturnedMessageTriggerService triggerService;
    private final ReturnedMessageConfirmationService confirmationService;
    private final ReturnedMessageExecutionResultService resultService;
    private final AiExecutionGateway executionGateway;
    private final ReturnedMessageProperties properties;
    private final String workerId = UUID.randomUUID().toString();

    public ReturnedMessageMaintenanceService(
            ReturnedMessageRepository repository,
            ReturnedMessageTriggerService triggerService,
            ReturnedMessageConfirmationService confirmationService,
            ReturnedMessageExecutionResultService resultService,
            AiExecutionGateway executionGateway,
            ReturnedMessageProperties properties) {
        this.repository = repository;
        this.triggerService = triggerService;
        this.confirmationService = confirmationService;
        this.resultService = resultService;
        this.executionGateway = executionGateway;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${returned-message.expiration-sweep-interval}")
    public int expireConfirmations() {
        if (!properties.enabled()) {
            return 0;
        }
        return repository.expireConfirmations(properties.expirationBatchSize());
    }

    @Scheduled(fixedDelayString = "${returned-message.recovery-sweep-interval}")
    public void recoverNonTerminalLifecycles() {
        if (!properties.enabled()) {
            return;
        }
        for (ReturnedMessageLifecycle lifecycle
                : repository.findRecoverable(properties.recoveryBatchSize())) {
            if (!repository.tryClaim(
                    lifecycle.tenantId(), lifecycle.requestId(), workerId,
                    properties.recoveryClaimDuration())) {
                continue;
            }
            try {
                recover(lifecycle);
            } catch (RuntimeException exception) {
                logger.warn("Returned Message recovery attempt failed. requestId={}",
                        lifecycle.requestId(), exception);
            } finally {
                repository.releaseClaim(lifecycle.tenantId(), lifecycle.requestId(), workerId);
            }
        }
    }

    private void recover(ReturnedMessageLifecycle lifecycle) {
        switch (lifecycle.status()) {
            case DETECTED -> triggerService.resumeDetection(lifecycle);
            case AWAITING_CONFIRMATION -> {
                if (lifecycle.confirmationDeliveryStatus() == ConfirmationDeliveryStatus.PENDING
                        && lifecycle.confirmationExpiresAt() != null
                        && lifecycle.confirmationExpiresAt().isAfter(repository.currentTime())) {
                    triggerService.resendConfirmation(lifecycle);
                }
            }
            case PROCESSING -> reconcile(lifecycle);
            case DELIVERY_PENDING -> resultService.retryDelivery(lifecycle);
            default -> {
                // Query only returns non-terminal rows; this guards future enum additions.
            }
        }
    }

    private void reconcile(ReturnedMessageLifecycle lifecycle) {
        AiExecutionGateway.ExecutionResult result = executionGateway.reconcile(
                lifecycle.tenantId(), lifecycle.executionId());
        if (result.status() == AiExecutionGateway.Status.NOT_FOUND) {
            confirmationService.redispatch(lifecycle);
            return;
        }
        resultService.handle(
                lifecycle.tenantId(), lifecycle.userId(), lifecycle.requestId(),
                lifecycle.executionId(), result);
    }
}
