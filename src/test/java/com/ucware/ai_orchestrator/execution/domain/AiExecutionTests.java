package com.ucware.ai_orchestrator.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.result.domain.RoutingRef;

class AiExecutionTests {

    @Test
    void managesCommonExecutionLifecycle() {
        Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant scheduledFor = Instant.parse("2026-01-01T00:00:10Z");
        AiExecution execution = AiExecution.create(
                "execution-1", AiWorkflowType.CONVERSATION_START, routingRef(),
                requestedAt, scheduledFor);

        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.CREATED);

        execution.schedule();
        execution.start();
        execution.complete();

        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.COMPLETED);
        assertThat(execution.isActive()).isFalse();
        assertThat(execution.cancel()).isFalse();
    }

    @Test
    void rejectsInvalidStateTransition() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AiExecution execution = AiExecution.create(
                "execution-1", AiWorkflowType.CONVERSATION_START, routingRef(), now, now);

        assertThatThrownBy(execution::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only scheduled executions can start");
    }

    private static RoutingRef routingRef() {
        return RoutingRef.forClientSession(
                "default", "user-1", "client-session-1", null);
    }
}
