package com.ucware.ai_orchestrator.conversationstart.application;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;

public interface StartConversationSuggestionUseCase {
    ConversationStart start(String roomSessionId, String userId, String roomId, String chatType);
}
