package com.ucware.ai_orchestrator.returnedmessage.domain;

import java.time.Instant;
import java.util.Objects;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;

public record ReturnedMessageLifecycle(
        String requestId,
        String triggerId,
        String tenantId,
        String userId,
        String absencePeriodId,
        Instant offlineSince,
        Instant returnedAt,
        Instant triggerExpiresAt,
        RoutingRef routingRef,
        ReturnedMessageStatus status,
        String messageSelectionRef,
        Integer unreadCount,
        Instant selectedAt,
        Instant selectionExpiresAt,
        String confirmationId,
        ConfirmationDeliveryStatus confirmationDeliveryStatus,
        Instant confirmationExpiresAt,
        String executionId,
        String resultRef,
        String deliveryId,
        ReturnedMessageFailureStage failureStage,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public ReturnedMessageLifecycle {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(triggerId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(absencePeriodId);
        Objects.requireNonNull(offlineSince);
        Objects.requireNonNull(returnedAt);
        Objects.requireNonNull(triggerExpiresAt);
        Objects.requireNonNull(routingRef);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }
}
