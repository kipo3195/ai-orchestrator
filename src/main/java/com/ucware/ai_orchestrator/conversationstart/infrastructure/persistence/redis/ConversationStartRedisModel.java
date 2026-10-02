package com.ucware.ai_orchestrator.conversationstart.infrastructure.persistence.redis;

public record ConversationStartRedisModel(
        String executionId,
        String userId,
        String roomId,
        String status
) {
}