package com.ucware.ai_orchestrator.result.domain;

public record DeliveryEvent(
        String executionId,
        String eventId,
        String eventType,
        String connectionId,
        String userId,
        RoutingScope scopeRef,
        Object payload) {
}
