package com.ucware.ai_orchestrator.result.infrastructure.config;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.result.application.port.RealtimeInstanceEndpointResolver;

@Component 
public class ConfiguredRealtimeInstanceEndpointResolver  implements RealtimeInstanceEndpointResolver {

      private final Map<String, URI> endpoints;

    public ConfiguredRealtimeInstanceEndpointResolver(
            ResultRouterProperties properties) {
        this.endpoints = properties.delivery().ownerBaseUrls();
    }

      @Override
    public Optional<URI> resolve(String ownerInstanceId) {
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(endpoints.get(ownerInstanceId));
    }
    
    
}
