package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import com.ucware.ai_orchestrator.result.application.AiResultRouter;
import com.ucware.ai_orchestrator.result.application.port.RealtimeConnectionResolver;
import com.ucware.ai_orchestrator.result.application.port.ResultDeliveryPort;
import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.domain.LifecycleEvent;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageResultSender;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

@ExtendWith(OutputCaptureExtension.class)
class RealtimeReturnedMessageResultSenderTests {

    private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");

    private RealtimeConnectionResolver connectionResolver;
    private ResultDeliveryPort deliveryPort;
    private RealtimeReturnedMessageResultSender sender;

    @BeforeEach
    void setUp() {
        connectionResolver = mock(RealtimeConnectionResolver.class);
        deliveryPort = mock(ResultDeliveryPort.class);
        sender = new RealtimeReturnedMessageResultSender(
                new AiResultRouter(connectionResolver, deliveryPort));
    }

    @Test
    void routesReturnedMessageResultToCurrentRealtimeOwner() {
        RoutingRef routingRef = routingRef();
        DeliveryTarget target = new DeliveryTarget("connection-1", "WS1");
        ReturnedMessageTopicsResult result = result();
        LifecycleEvent lifecycleEvent = new LifecycleEvent(
                "returned_message_topic", "request-1", "client-event-3", 3,
                "TOPICS_READY", NOW);
        when(connectionResolver.resolve(routingRef)).thenReturn(Optional.of(target));

        ReturnedMessageResultSender.DeliveryOutcome outcome = sender.send(
                new ReturnedMessageResultSender.ResultDelivery(
                        "request-1",
                        "execution-1",
                        "delivery-1",
                        result,
                        lifecycleEvent,
                        routingRef));

        assertThat(outcome).isEqualTo(
                ReturnedMessageResultSender.DeliveryOutcome.DELIVERED);
        ArgumentCaptor<DeliveryEvent> eventCaptor = ArgumentCaptor.forClass(DeliveryEvent.class);
        verify(deliveryPort).deliver(eq(target), eventCaptor.capture());
        DeliveryEvent event = eventCaptor.getValue();
        assertThat(event.executionId()).isEqualTo("execution-1");
        assertThat(event.eventId()).isEqualTo("delivery-1");
        assertThat(event.eventType()).isEqualTo("RETURNED_MESSAGE_TOPICS_COMPLETED");
        assertThat(event.connectionId()).isEqualTo("connection-1");
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.lifecycleEvent()).isEqualTo(lifecycleEvent);
        assertThat(event.scopeRef()).isEqualTo(routingRef.scope());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(event.payload());
        assertThat(json.path("topics").get(0).path("messages").get(0).path("subject").asText())
                .isEqualTo("제목 1");
        assertThat(json.findValues("priorityReason")).isEmpty();
        assertThat(json.findValues("replyReason")).isEmpty();
        assertThat(json.findValues("actionReason")).isEmpty();
        assertThat(json.has("messageAssessments")).isFalse();
        assertThat(json.path("counts").size()).isEqualTo(5);
        assertThat(json.path("counts").path("total").asInt()).isEqualTo(1);
        assertThat(json.path("counts").path("urgent").asInt()).isZero();
        assertThat(json.path("counts").path("replyRequired").asInt()).isEqualTo(1);
        assertThat(json.path("counts").path("actionRequired").asInt()).isEqualTo(1);
        assertThat(json.path("counts").path("other").asInt()).isZero();
    }

    @Test
    void keepsDeliveryRetryableWhenCurrentRealtimeOwnerDoesNotExist(CapturedOutput output) {
        RoutingRef routingRef = routingRef();
        when(connectionResolver.resolve(routingRef)).thenReturn(Optional.empty());

        ReturnedMessageResultSender.DeliveryOutcome outcome = sender.send(
                new ReturnedMessageResultSender.ResultDelivery(
                        "request-1",
                        "execution-1",
                        "delivery-1",
                        result(),
                        routingRef));

        assertThat(outcome).isEqualTo(
                ReturnedMessageResultSender.DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(output)
                .contains("reason=No active realtime connection for execution execution-1")
                .doesNotContain("ResultRoutingException:");
    }

    private RoutingRef routingRef() {
        return RoutingRef.forClientSession(
                "tenant-1",
                "user-1",
                "client-session-1",
                new RoutingScope("ABSENCE_PERIOD", "period-1"),
                1);
    }

    private ReturnedMessageTopicsResult result() {
        return new ReturnedMessageTopicsResult(
                List.of(new ReturnedMessageTopicsResult.Topic(
                        "제목 1",
                        "요약",
                        List.of("message-1"))),
                List.of(new ReturnedMessageTopicsResult.MessageAssessment(
                        "message-1",
                        "제목 1",
                        ReturnedMessageTopicsResult.Priority.NORMAL,
                        null,
                        true,
                        "답변 요청이 있습니다.",
                        true,
                        "후속 조치 요청이 있습니다.")),
                NOW);
    }
}
