package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageFailureStage;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

public interface ReturnedMessageRepository {

    Instant currentTime();

    LifecycleCreationResult create(ReturnedMessageRequest request);

    Optional<ReturnedMessageLifecycle> findOwned(String tenantId, String userId, String requestId);

    boolean markNoMessages(String tenantId, String requestId);

    boolean awaitConfirmation(
            String tenantId,
            String requestId,
            String messageSelectionRef,
            int unreadCount,
            Instant selectedAt,
            Instant selectionExpiresAt,
            String confirmationId,
            Duration confirmationTimeout);

    boolean failDetection(String tenantId, String requestId, String safeReason);

    boolean decline(
            String tenantId,
            String userId,
            String requestId,
            String absencePeriodId,
            String confirmationId,
            String messageSelectionRef);

    boolean blockByPolicy(
            String tenantId,
            String userId,
            String requestId,
            String confirmationId,
            String safeReason);

    boolean startProcessing(
            String tenantId,
            String userId,
            String requestId,
            String confirmationId,
            String executionId);

    boolean expireIfDue(String tenantId, String userId, String requestId);

    boolean expireTriggerIfDue(String tenantId, String requestId);

    int expireConfirmations(int batchSize);

    boolean executionSucceeded(
            String tenantId,
            String requestId,
            String executionId,
            ReturnedMessageTopicsResult result,
            String deliveryId);

    boolean executionFailed(
            String tenantId,
            String requestId,
            String executionId,
            ReturnedMessageFailureStage failureStage,
            String safeReason);

    boolean executionCancelled(String tenantId, String requestId, String executionId);

    boolean deliveryCompleted(
            String tenantId,
            String requestId,
            String executionId,
            String deliveryId);

    boolean deliveryFailed(
            String tenantId,
            String requestId,
            String executionId,
            String deliveryId,
            String safeReason);

    List<ReturnedMessageLifecycle> findRecoverable(int batchSize);

    boolean tryClaim(
            String tenantId,
            String requestId,
            String ownerToken,
            Duration leaseDuration);

    void releaseClaim(String tenantId, String requestId, String ownerToken);
}
