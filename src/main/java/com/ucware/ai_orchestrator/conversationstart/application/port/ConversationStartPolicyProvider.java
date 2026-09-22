package com.ucware.ai_orchestrator.conversationstart.application.port;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartPolicyConfig;

public interface ConversationStartPolicyProvider {
    ConversationStartPolicyConfig getPolicy(String tenantId, String userId);
}