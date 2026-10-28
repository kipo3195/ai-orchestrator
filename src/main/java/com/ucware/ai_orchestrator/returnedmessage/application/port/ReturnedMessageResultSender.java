package com.ucware.ai_orchestrator.returnedmessage.application.port;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

public interface ReturnedMessageResultSender {

    DeliveryOutcome send(ResultDelivery command);

    record ResultDelivery(
            String requestId,
            String executionId,
            String deliveryId,
            ReturnedMessageTopicsResult result,
            RoutingRef routingRef) {
    }

    enum DeliveryOutcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
