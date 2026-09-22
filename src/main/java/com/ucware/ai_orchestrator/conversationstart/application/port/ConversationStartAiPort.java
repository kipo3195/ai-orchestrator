package com.ucware.ai_orchestrator.conversationstart.application.port;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartContext;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartResult;

public interface ConversationStartAiPort {
    ConversationStartResult generateSuggestion(
            ConversationStartContext context
    );
}
