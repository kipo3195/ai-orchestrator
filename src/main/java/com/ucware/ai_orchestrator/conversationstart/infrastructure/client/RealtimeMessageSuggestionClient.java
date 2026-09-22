package com.ucware.ai_orchestrator.conversationstart.infrastructure.client;

import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationSuggestionSender;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartResult;

public class RealtimeMessageSuggestionClient implements ConversationSuggestionSender {

    @Override
    public void send(String userId, String roomId, ConversationStartResult result) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'send'");
    }
    
}
