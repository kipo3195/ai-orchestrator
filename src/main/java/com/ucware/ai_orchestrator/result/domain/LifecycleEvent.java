package com.ucware.ai_orchestrator.result.domain;

import java.time.Instant;

public record LifecycleEvent(
        String type,
        String lifecycleId,
        String eventId,
        long revision,
        String state,
        Instant occurredAt) {
}
