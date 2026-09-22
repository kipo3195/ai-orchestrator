package com.ucware.ai_orchestrator.conversationstart.domain;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class ConversationStartDelayPolicy {

    public Duration calculate(
            Instant enteredAt,
            Duration triggerDelay
    ) {
        Instant executeAt = enteredAt.plus(triggerDelay);

        Duration remaining =
                Duration.between(Instant.now(), executeAt);

        return remaining.isNegative()
                ? Duration.ZERO
                : remaining;
    }
}