package com.ucware.ai_orchestrator.result.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

public record DeliveryEvent(
        String executionId,
        String eventId,
        String eventType,
        String connectionId,
        String userId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LifecycleEvent lifecycleEvent,
        RoutingScope scopeRef,
        Object payload) {

    public DeliveryEvent(
            String executionId,
            String eventId,
            String eventType,
            String connectionId,
            String userId,
            RoutingScope scopeRef,
            Object payload) {
        this(executionId, eventId, eventType, connectionId, userId, null, scopeRef, payload);
    }
}
