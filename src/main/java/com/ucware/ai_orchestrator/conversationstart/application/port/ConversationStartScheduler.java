package com.ucware.ai_orchestrator.conversationstart.application.port;

import java.time.Duration;

public interface ConversationStartScheduler {
    
    void schedule(
        String executionId,
        Duration delay,
        Runnable task
    );

    void cancel(String executionId);
}
