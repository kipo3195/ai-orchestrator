package com.ucware.ai_orchestrator.execution.application;

import java.time.Instant;

import com.ucware.ai_orchestrator.execution.domain.AiExecution;
import com.ucware.ai_orchestrator.execution.domain.AiExecutionStatus;
import com.ucware.ai_orchestrator.execution.domain.AiWorkflowType;

public record AiExecutionResponse<T>(
        String executionId,
        AiWorkflowType workflowType,
        AiExecutionStatus status,
        Instant requestedAt,
        Instant scheduledFor,
        T data
) {
    public static <T> AiExecutionResponse<T> from(AiExecution execution, T data) {
        return new AiExecutionResponse<>(
                execution.getExecutionId(),
                execution.getWorkflowType(),
                execution.getStatus(),
                execution.getRequestedAt(),
                execution.getScheduledFor(),
                data);
    }
}
