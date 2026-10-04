package com.ucware.ai_orchestrator.result.application;

import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.result.domain.RoutingRef;
import com.ucware.ai_orchestrator.result.domain.RoutingScope;
import com.ucware.ai_orchestrator.result.infrastructure.config.ResultRouterProperties;

@Component
public class RoutingRefFactory {

    private final String defaultTenantId;

    public RoutingRefFactory(ResultRouterProperties properties) {
        this.defaultTenantId = properties.defaultTenantId();
    }

    public RoutingRef forClientSession(
            String userId,
            String clientSessionId,
            RoutingScope scope) {
        return RoutingRef.forClientSession(
                defaultTenantId, userId, clientSessionId, scope);
    }
}
