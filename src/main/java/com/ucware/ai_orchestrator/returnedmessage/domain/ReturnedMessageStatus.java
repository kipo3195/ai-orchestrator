package com.ucware.ai_orchestrator.returnedmessage.domain;

public enum ReturnedMessageStatus {
    DETECTED(false),
    AWAITING_CONFIRMATION(false),
    PROCESSING(false),
    DELIVERY_PENDING(false),
    SKIPPED_NO_MESSAGES(true),
    BLOCKED_BY_POLICY(true),
    DECLINED(true),
    EXPIRED(true),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    DELIVERY_FAILED(true);

    private final boolean terminal;

    ReturnedMessageStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
