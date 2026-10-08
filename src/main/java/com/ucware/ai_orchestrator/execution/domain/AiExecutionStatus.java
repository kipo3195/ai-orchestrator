package com.ucware.ai_orchestrator.execution.domain;

public enum AiExecutionStatus {
    CREATED,
    SCHEDULED,
    EXECUTING,
    COMPLETED,
    FAILED,
    REJECTED,
    CANCELLED
}
