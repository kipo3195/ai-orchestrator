package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "returned-message.async")
public record ReturnedMessageAsyncProperties(
        @Min(1) int executionPoolSize,
        @Min(1) int deliveryPoolSize,
        @Min(1) int queueCapacity) {
}
