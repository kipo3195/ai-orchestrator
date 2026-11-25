package com.ucware.ai_orchestrator.returnedmessage.domain;

public enum ReturnedMessageClientEventState {
    AWAITING_CONFIRMATION,
    PROCESSING,
    TOPICS_READY,
    DECLINED,
    EXPIRED,
    UNAVAILABLE,
    FAILED,
    CANCELLED;

    public static ReturnedMessageClientEventState from(ReturnedMessageStatus status) {
        return switch (status) {
            case AWAITING_CONFIRMATION -> AWAITING_CONFIRMATION;
            case PROCESSING -> PROCESSING;
            case DELIVERY_PENDING, COMPLETED -> TOPICS_READY;
            case DECLINED -> DECLINED;
            case EXPIRED -> EXPIRED;
            case BLOCKED_BY_POLICY, SKIPPED_NO_MESSAGES -> UNAVAILABLE;
            case FAILED, DELIVERY_FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
            case DETECTED -> throw new IllegalArgumentException(
                    "DETECTED does not have a public client event state");
        };
    }
}
