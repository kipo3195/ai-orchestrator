package com.ucware.ai_orchestrator.conversationstart.application.port;

import java.util.List;

import com.ucware.ai_orchestrator.conversationstart.domain.MessageContext;

public interface MessageContextPort {
    List<MessageContext> getRecentMessages(String userId, String roomId);
}