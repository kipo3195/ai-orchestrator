package com.ucware.ai_orchestrator.result.domain;

import java.util.Objects;

public record ResultEvent(
        String executionId,
        String eventId,
        String eventType,
        LifecycleEvent lifecycleEvent,
        Object payload) {

    public ResultEvent(String executionId, String eventId, String eventType, Object payload) {
        this(executionId, eventId, eventType, null, payload);
    }

    public ResultEvent {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(payload);
    }
}
