package com.ucware.ai_orchestrator.returnedmessage.domain;

import java.time.Instant;
import java.util.Objects;

public record ReturnedMessageConfirmation(
        String tenantId,
        String userId,
        String requestId,
        String absencePeriodId,
        String confirmationId,
        String messageSelectionRef,
        ReturnedMessageDecision decision,
        Instant decidedAt,
        String targetRef,
        int connectionType) {

    public ReturnedMessageConfirmation {
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        requireText(requestId, "requestId");
        requireText(absencePeriodId, "absencePeriodId");
        requireText(confirmationId, "confirmationId");
        requireText(messageSelectionRef, "messageSelectionRef");
        requireText(targetRef, "targetRef");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
