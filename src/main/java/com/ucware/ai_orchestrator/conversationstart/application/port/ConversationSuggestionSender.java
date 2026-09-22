package com.ucware.ai_orchestrator.conversationstart.application.port;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartResult;

public interface ConversationSuggestionSender {
       void send(
            String userId,
            String roomId,
            ConversationStartResult result
    );
}
