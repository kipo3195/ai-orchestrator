package com.ucware.ai_orchestrator.result.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ucware.ai_orchestrator.execution.domain.AiExecution;
import com.ucware.ai_orchestrator.execution.domain.AiWorkflowType;
import com.ucware.ai_orchestrator.result.application.port.RealtimeConnectionResolver;
import com.ucware.ai_orchestrator.result.application.port.ResultDeliveryPort;
import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.domain.ResultEvent;
import com.ucware.ai_orchestrator.result.domain.RoutingRef;
import com.ucware.ai_orchestrator.result.domain.RoutingScope;

class AiResultRouterTests {

    @Test
    void resolvesCurrentOwnerAndDeliversUsingConnectionId() {
        RealtimeConnectionResolver resolver = mock(RealtimeConnectionResolver.class);
        ResultDeliveryPort deliveryPort = mock(ResultDeliveryPort.class);
        AiResultRouter router = new AiResultRouter(resolver, deliveryPort);
        RoutingRef routingRef = RoutingRef.forClientSession(
                "default", "user-1", "client-session-1",
                new RoutingScope("ROOM_SESSION", "room-session-1"));
        AiExecution execution = AiExecution.create(
                "execution-1", AiWorkflowType.CONVERSATION_START, routingRef,
                Instant.now(), Instant.now());
        DeliveryTarget target = new DeliveryTarget("connection-1", "realtime-2");
        when(resolver.resolve(routingRef)).thenReturn(Optional.of(target));

        router.route(execution, new ResultEvent(
                "execution-1", "event-1", "COMPLETED", "payload"));

        ArgumentCaptor<DeliveryEvent> eventCaptor = ArgumentCaptor.forClass(DeliveryEvent.class);
        verify(deliveryPort).deliver(org.mockito.ArgumentMatchers.eq(target), eventCaptor.capture());
        DeliveryEvent delivered = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(delivered.connectionId()).isEqualTo("connection-1");
        org.assertj.core.api.Assertions.assertThat(delivered.userId()).isEqualTo("user-1");
        org.assertj.core.api.Assertions.assertThat(delivered.scopeRef()).isEqualTo(routingRef.scope());
    }

    @Test
    void failsWhenCurrentConnectionDoesNotExist() {
        RealtimeConnectionResolver resolver = mock(RealtimeConnectionResolver.class);
        ResultDeliveryPort deliveryPort = mock(ResultDeliveryPort.class);
        AiResultRouter router = new AiResultRouter(resolver, deliveryPort);
        RoutingRef routingRef = RoutingRef.forClientSession(
                "default", "user-1", "client-session-1", null);
        AiExecution execution = AiExecution.create(
                "execution-1", AiWorkflowType.CONVERSATION_START, routingRef,
                Instant.now(), Instant.now());
        when(resolver.resolve(routingRef)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> router.route(execution, new ResultEvent(
                "execution-1", "event-1", "COMPLETED", "payload")))
                .isInstanceOf(ResultRoutingException.class)
                .hasMessageContaining("No active realtime connection");
    }
}
