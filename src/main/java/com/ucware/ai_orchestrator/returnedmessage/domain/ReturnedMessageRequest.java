package com.ucware.ai_orchestrator.returnedmessage.domain;

import java.time.Instant;
import java.util.Objects;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;

public record ReturnedMessageRequest(
        String requestId,
        String triggerId,
        String tenantId,
        String userId,
        String absencePeriodId,
        Instant offlineSince,
        Instant returnedAt,
        Instant occurredAt,
        Instant triggerExpiresAt,
        RoutingRef routingRef) {

    public ReturnedMessageRequest {
        requireText(requestId, "requestId");
        requireText(triggerId, "triggerId");
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        requireText(absencePeriodId, "absencePeriodId");
        Objects.requireNonNull(offlineSince, "offlineSince must not be null");
        Objects.requireNonNull(returnedAt, "returnedAt must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(triggerExpiresAt, "triggerExpiresAt must not be null");
        Objects.requireNonNull(routingRef, "routingRef must not be null");
        if (!tenantId.equals(routingRef.tenantId()) || !userId.equals(routingRef.userId())) {
            throw new IllegalArgumentException("routingRef owner does not match request owner");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
