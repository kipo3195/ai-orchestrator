package com.ucware.ai_orchestrator.returnedmessage.application;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;

public record ReturnedMessageOperationResult(
        Outcome outcome,
        String reason,
        ReturnedMessageLifecycle lifecycle) {

    public enum Outcome {
        REJECTED,
        CREATED,
        EXISTING,
        UPDATED
    }

    public static ReturnedMessageOperationResult rejected(String reason) {
        return new ReturnedMessageOperationResult(Outcome.REJECTED, reason, null);
    }

    public static ReturnedMessageOperationResult created(ReturnedMessageLifecycle lifecycle) {
        return new ReturnedMessageOperationResult(Outcome.CREATED, null, lifecycle);
    }

    public static ReturnedMessageOperationResult existing(ReturnedMessageLifecycle lifecycle) {
        return new ReturnedMessageOperationResult(Outcome.EXISTING, null, lifecycle);
    }

    public static ReturnedMessageOperationResult updated(ReturnedMessageLifecycle lifecycle) {
        return new ReturnedMessageOperationResult(Outcome.UPDATED, null, lifecycle);
    }
}
