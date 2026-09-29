package com.ucware.ai_orchestrator.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AiExecutionTests {

    @Test
    void managesCommonExecutionLifecycle() {
        Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant scheduledFor = Instant.parse("2026-01-01T00:00:10Z");
        AiExecution execution = AiExecution.create(
                "execution-1", AiWorkflowType.CONVERSATION_START, requestedAt, scheduledFor);

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
                "execution-1", AiWorkflowType.CONVERSATION_START, now, now);

        assertThatThrownBy(execution::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only scheduled executions can start");
    }
}
