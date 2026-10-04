package com.ucware.ai_orchestrator.result.application.port;

import java.net.URI;
import java.util.Optional;

public interface RealtimeInstanceEndpointResolver {
    // Server Instance의 Connection
    Optional<URI> resolve(String ownerInstanceId);
}
