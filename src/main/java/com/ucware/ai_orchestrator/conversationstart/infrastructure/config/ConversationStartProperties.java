package com.ucware.ai_orchestrator.conversationstart.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conversation-start")
public record ConversationStartProperties(
        boolean enabled,
        Duration triggerDelay,
        Duration cooldown
) {
}