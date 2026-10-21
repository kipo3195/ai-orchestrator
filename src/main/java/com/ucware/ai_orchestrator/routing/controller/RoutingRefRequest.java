package com.ucware.ai_orchestrator.routing.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

public record RoutingRefRequest(
        @NotBlank String tenantId,
        @NotBlank String userId,
        @NotBlank String targetRef,
        @Positive int connectionType) {

    public RoutingRef toRoutingRef(RoutingScope scope) {
        return RoutingRef.forClientSession(
                tenantId, userId, targetRef, scope, connectionType);
    }
}
