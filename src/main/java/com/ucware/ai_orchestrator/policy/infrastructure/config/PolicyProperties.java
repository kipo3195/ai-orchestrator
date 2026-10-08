package com.ucware.ai_orchestrator.policy.infrastructure.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "policy")
public record PolicyProperties(
        Duration snapshotRefreshInterval) {

    public PolicyProperties {
        Objects.requireNonNull(snapshotRefreshInterval);
        if (snapshotRefreshInterval.isZero() || snapshotRefreshInterval.isNegative()) {
            throw new IllegalArgumentException("policy.snapshot-refresh-interval must be positive");
        }
    }
}
