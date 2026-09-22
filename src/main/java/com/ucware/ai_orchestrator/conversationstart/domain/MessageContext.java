package com.ucware.ai_orchestrator.conversationstart.domain;
import java.time.LocalDateTime;

public record MessageContext(
        String senderId,
        String content,
        LocalDateTime sentAt
) {
}