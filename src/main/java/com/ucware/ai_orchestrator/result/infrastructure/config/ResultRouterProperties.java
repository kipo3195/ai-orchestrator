package com.ucware.ai_orchestrator.result.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "result-router")
public record ResultRouterProperties(
        String defaultTenantId,
        Delivery delivery) {

    public ResultRouterProperties {
        Objects.requireNonNull(defaultTenantId);
        Objects.requireNonNull(delivery);
    }

    public record Delivery(
            Map<String, URI> ownerBaseUrls,
            String path,
            Duration connectTimeout,
            Duration requestTimeout) {

        public Delivery {
            Objects.requireNonNull(ownerBaseUrls);
            Objects.requireNonNull(path);
            Objects.requireNonNull(connectTimeout);
            Objects.requireNonNull(requestTimeout);

            ownerBaseUrls = Map.copyOf(ownerBaseUrls);
        }
    }
}
