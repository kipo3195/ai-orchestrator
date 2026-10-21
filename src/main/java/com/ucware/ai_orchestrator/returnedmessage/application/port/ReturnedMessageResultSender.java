package com.ucware.ai_orchestrator.returnedmessage.application.port;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;

public interface ReturnedMessageResultSender {

    DeliveryOutcome send(ResultDelivery command);

    record ResultDelivery(
            String requestId,
            String executionId,
            String deliveryId,
            String resultRef,
            RoutingRef routingRef) {
    }

    enum DeliveryOutcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
