package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "returned-message")
public record ReturnedMessageProperties(
        boolean enabled,
        @NotNull Duration minAwayDuration,
        @NotNull Duration maxAbsenceDuration,
        @NotNull Duration maxLookback,
        @NotNull Duration confirmationTimeout,
        @NotNull Duration expirationSweepInterval,
        @Min(1) int expirationBatchSize,
        @NotNull Duration recoveryClaimDuration,
        @NotNull Duration recoverySweepInterval,
        @Min(1) int recoveryBatchSize) {

    public ReturnedMessageProperties {
        requirePositive(minAwayDuration, "min-away-duration");
        requirePositive(maxAbsenceDuration, "max-absence-duration");
        requirePositive(maxLookback, "max-lookback");
        requirePositive(confirmationTimeout, "confirmation-timeout");
        requirePositive(expirationSweepInterval, "expiration-sweep-interval");
        requirePositive(recoveryClaimDuration, "recovery-claim-duration");
        requirePositive(recoverySweepInterval, "recovery-sweep-interval");
        if (minAwayDuration != null && maxAbsenceDuration != null
                && maxAbsenceDuration.compareTo(minAwayDuration) < 0) {
            throw new IllegalArgumentException(
                    "returned-message.max-absence-duration must be >= min-away-duration");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("returned-message." + name + " must be positive");
        }
    }
}
