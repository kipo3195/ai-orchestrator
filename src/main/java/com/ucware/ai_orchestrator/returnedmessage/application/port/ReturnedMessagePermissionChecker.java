package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.util.Objects;

public interface ReturnedMessagePermissionChecker {

    // Java는 추상 메서드가 하나뿐인 인터페이스를 람다식으로 구현할 수 있습니다.
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
