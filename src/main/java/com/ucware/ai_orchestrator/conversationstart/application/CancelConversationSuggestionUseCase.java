package com.ucware.ai_orchestrator.conversationstart.application;

public interface CancelConversationSuggestionUseCase {
    boolean cancel(String userId, String roomId, String executionId);
}
