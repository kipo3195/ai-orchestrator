package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.result.application.AiResultRouter;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageClientResult;
import com.ucware.ai_orchestrator.result.application.ResultRoutingException;
import com.ucware.ai_orchestrator.result.domain.ResultEvent;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageResultSender;

@Component
public class RealtimeReturnedMessageResultSender implements ReturnedMessageResultSender {

    private static final Logger logger = LoggerFactory.getLogger(
            RealtimeReturnedMessageResultSender.class);
    private static final String EVENT_TYPE = "RETURNED_MESSAGE_TOPICS_COMPLETED";

    private final AiResultRouter resultRouter;

    public RealtimeReturnedMessageResultSender(AiResultRouter resultRouter) {
        this.resultRouter = resultRouter;
    }

    @Override
    public DeliveryOutcome send(ResultDelivery command) {
        try {
            resultRouter.route(
                    command.routingRef(),
                    new ResultEvent(
                            command.executionId(),
                            command.deliveryId(),
                            EVENT_TYPE,
                            command.lifecycleEvent(),
                            ReturnedMessageClientResult.from(command.result())));
            return DeliveryOutcome.DELIVERED;
        } catch (ResultRoutingException exception) {
            logger.warn(
                    "Returned Message realtime delivery is retryable. "
                            + "requestId={}, executionId={}, deliveryId={}, reason={}",
                    command.requestId(), command.executionId(), command.deliveryId(),
                    exception.getMessage());
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }
    }
}
