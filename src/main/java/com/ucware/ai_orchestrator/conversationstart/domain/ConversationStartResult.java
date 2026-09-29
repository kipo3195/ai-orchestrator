package com.ucware.ai_orchestrator.conversationstart.domain;

import java.util.List;

public record ConversationStartResult(String executionId, List<SuggestionItem> suggestions) {
    public ConversationStartResult {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
