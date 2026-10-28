package com.ucware.ai_orchestrator.returnedmessage.domain;

import java.time.Instant;
import java.util.List;

public record ReturnedMessageSelection(
        String messageSelectionRef,
        String requestId,
        String tenantId,
        String userId,
        String absencePeriodId,
        Instant selectedAt,
        Instant expiresAt,
        List<Item> items) {

    public ReturnedMessageSelection {
        items = List.copyOf(items);
    }

    public record Item(
            String sourceSystem,
            String sourceItemType,
            String sourceItemKey,
            String subject,
            int sequence) {
    }
}
