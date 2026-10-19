package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.time.Instant;

public interface ReturnedMessageQuery {

    MessageSelection selectUnreadMessages(MessageSelectionQuery query);

    record MessageSelectionQuery(
            String requestId,
            String tenantId,
            String userId,
            String absencePeriodId,
            Instant offlineSince,
            Instant lookbackSince,
            Instant returnedAt) {
    }

    record MessageSelection(
            String absencePeriodId,
            int unreadCount,
            String messageSelectionRef,
            Instant selectedAt,
            Instant selectionExpiresAt) {
    }
}
