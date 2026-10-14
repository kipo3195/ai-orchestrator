package com.ucware.ai_orchestrator.policy.infrastructure.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "policy")
public record PolicyProperties(
        Duration snapshotRefreshInterval,
        Duration userCacheTtl,
        Duration userNegativeCacheTtl,
        int userFallbackMaxConcurrency) {

    public PolicyProperties {
        Objects.requireNonNull(snapshotRefreshInterval);
        Objects.requireNonNull(userCacheTtl);
        Objects.requireNonNull(userNegativeCacheTtl);
        if (snapshotRefreshInterval.isZero() || snapshotRefreshInterval.isNegative()) {
            throw new IllegalArgumentException("policy.snapshot-refresh-interval must be positive");
        }
        if (userCacheTtl.isZero() || userCacheTtl.isNegative()) {
            throw new IllegalArgumentException("policy.user-cache-ttl must be positive");
        }
        if (userNegativeCacheTtl.isZero() || userNegativeCacheTtl.isNegative()) {
            throw new IllegalArgumentException("policy.user-negative-cache-ttl must be positive");
        }
        if (userFallbackMaxConcurrency <= 0) {
            throw new IllegalArgumentException("policy.user-fallback-max-concurrency must be positive");
        }
    }
}
