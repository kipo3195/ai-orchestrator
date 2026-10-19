package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.util.Objects;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;

public record LifecycleCreationResult(
        Outcome outcome,
        ReturnedMessageLifecycle lifecycle) {

    public LifecycleCreationResult {
        Objects.requireNonNull(outcome);
        if (outcome != Outcome.CONFLICT) {
            Objects.requireNonNull(lifecycle);
        }
    }

    public enum Outcome {
        CREATED,
        ALREADY_EXISTS,
        CONFLICT
    }

    public static LifecycleCreationResult created(ReturnedMessageLifecycle lifecycle) {
        return new LifecycleCreationResult(Outcome.CREATED, lifecycle);
    }

    public static LifecycleCreationResult alreadyExists(ReturnedMessageLifecycle lifecycle) {
        return new LifecycleCreationResult(Outcome.ALREADY_EXISTS, lifecycle);
    }

    public static LifecycleCreationResult conflict() {
        return new LifecycleCreationResult(Outcome.CONFLICT, null);
    }
}
