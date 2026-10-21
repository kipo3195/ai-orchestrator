package com.ucware.ai_orchestrator.conversationstart.application;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;

public interface StartConversationSuggestionUseCase {
    ConversationStart start(
            String roomSessionId,
            RoutingRef routingRef,
            String roomId,
            String chatType);
}
