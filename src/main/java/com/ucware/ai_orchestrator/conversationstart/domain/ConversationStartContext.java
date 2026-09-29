package com.ucware.ai_orchestrator.conversationstart.domain;

import java.time.Instant;
import java.util.List;

public record ConversationStartContext(
        String executionId,
        String roomSessionId,
        String userId,
        String roomId,
        Instant enteredAt,
        ConversationSuggestionKind suggestionKind,
        List<MessageContext> recentMessages
) {
}
