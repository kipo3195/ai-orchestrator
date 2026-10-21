package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.time.Instant;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;

public interface ReturnedMessageConfirmationSender {

    void send(ConfirmationPrompt prompt);

    record ConfirmationPrompt(
            String requestId,
            String absencePeriodId,
            String confirmationId,
            String messageSelectionRef,
            int unreadCount,
            Instant expiresAt,
            RoutingRef routingRef) {
    }
}
