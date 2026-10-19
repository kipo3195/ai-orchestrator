package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.util.Objects;

public interface ReturnedMessagePermissionChecker {

    Decision evaluate(PermissionRequest request);

    enum Decision {
        ALLOWED,
        DENIED,
        UNAVAILABLE
    }

    enum Purpose {
        MESSAGE_SELECTION,
        AI_CONTEXT
    }

    record PermissionRequest(
            String tenantId,
            String userId,
            String absencePeriodId,
            Purpose purpose,
            String messageSelectionRef) {

        public PermissionRequest {
            Objects.requireNonNull(purpose, "purpose must not be null");
            if (purpose == Purpose.AI_CONTEXT
                    && (messageSelectionRef == null || messageSelectionRef.isBlank())) {
                throw new IllegalArgumentException(
                        "messageSelectionRef is required for AI context permission");
            }
        }
    }
}
