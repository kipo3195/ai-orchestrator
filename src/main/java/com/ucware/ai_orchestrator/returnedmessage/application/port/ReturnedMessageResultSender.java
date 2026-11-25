package com.ucware.ai_orchestrator.returnedmessage.application.port;

import com.ucware.ai_orchestrator.result.domain.LifecycleEvent;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

public interface ReturnedMessageResultSender {

    DeliveryOutcome send(ResultDelivery command);

    record ResultDelivery(
            String requestId,
            String executionId,
            String deliveryId,
            ReturnedMessageTopicsResult result,
            LifecycleEvent lifecycleEvent,
            RoutingRef routingRef) {

        public ResultDelivery(
                String requestId,
                String executionId,
                String deliveryId,
                ReturnedMessageTopicsResult result,
                RoutingRef routingRef) {
            this(requestId, executionId, deliveryId, result, null, routingRef);
        }
    }

    enum DeliveryOutcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
