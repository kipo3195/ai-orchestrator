package com.ucware.ai_orchestrator.result.application;

import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.execution.domain.AiExecution;
import com.ucware.ai_orchestrator.result.application.port.RealtimeConnectionResolver;
import com.ucware.ai_orchestrator.result.application.port.ResultDeliveryPort;
import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.domain.ResultEvent;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;

@Service
public class AiResultRouter {

    private final RealtimeConnectionResolver connectionResolver;
    private final ResultDeliveryPort deliveryPort;

    public AiResultRouter(RealtimeConnectionResolver connectionResolver,
                          ResultDeliveryPort deliveryPort) {
        this.connectionResolver = connectionResolver;
        this.deliveryPort = deliveryPort;
    }

    public void route(AiExecution execution, ResultEvent resultEvent) {
        if (!execution.getExecutionId().equals(resultEvent.executionId())) {
            throw new IllegalArgumentException("Result event does not match execution");
        }

        route(execution.getRoutingRef(), resultEvent);
    }

    public void route(RoutingRef routingRef, ResultEvent resultEvent) {
        DeliveryTarget target = connectionResolver.resolve(routingRef)
                .orElseThrow(() -> new ResultRoutingException(
                        "No active realtime connection for execution " + resultEvent.executionId()));

        DeliveryEvent deliveryEvent = new DeliveryEvent(
                resultEvent.executionId(),
                resultEvent.eventId(),
                resultEvent.eventType(),
                target.connectionId(),
                routingRef.userId(),
                routingRef.scope(),
                resultEvent.payload());
        deliveryPort.deliver(target, deliveryEvent);
    }
}
