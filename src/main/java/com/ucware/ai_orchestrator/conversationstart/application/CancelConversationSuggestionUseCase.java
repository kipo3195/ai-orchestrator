package com.ucware.ai_orchestrator.conversationstart.application;

public interface CancelConversationSuggestionUseCase {
    boolean cancel(String roomSessionId, String userId, String roomId);
}
