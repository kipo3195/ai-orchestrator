package com.ucware.ai_orchestrator.result.application.port;

import java.util.Optional;

import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.domain.RoutingRef;

public interface RealtimeConnectionResolver {
    // Client Connection
    Optional<DeliveryTarget> resolve(RoutingRef routingRef);
}
