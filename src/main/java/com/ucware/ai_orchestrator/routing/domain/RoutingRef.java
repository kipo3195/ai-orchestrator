package com.ucware.ai_orchestrator.routing.domain;

import java.util.Objects;

public record RoutingRef(
        String tenantId,
        String userId,
        RoutingTargetType targetType,
        String clientSessionId,
        RoutingScope scope,
        Integer connectionType) {

    public RoutingRef {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(clientSessionId);
    }

    public static RoutingRef forClientSession(
            String tenantId,
            String userId,
            String clientSessionId,
            RoutingScope scope) {
        return forClientSession(tenantId, userId, clientSessionId, scope, null);
    }

    public static RoutingRef forClientSession(
            String tenantId,
            String userId,
            String clientSessionId,
            RoutingScope scope,
            Integer connectionType) {
        return new RoutingRef(
                tenantId,
                userId,
                RoutingTargetType.CLIENT_SESSION_CURRENT,
                clientSessionId,
                scope,
                connectionType);
    }
}
