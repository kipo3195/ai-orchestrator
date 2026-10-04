package com.ucware.ai_orchestrator.result.domain;

import java.util.Objects;

public record ResultEvent(
        String executionId,
        String eventId,
        String eventType,
        Object payload) {

    public ResultEvent {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(payload);
    }
}
