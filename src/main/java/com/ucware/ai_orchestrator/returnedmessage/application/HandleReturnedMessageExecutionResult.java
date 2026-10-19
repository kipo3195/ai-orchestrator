package com.ucware.ai_orchestrator.returnedmessage.application;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;

public interface HandleReturnedMessageExecutionResult {
    ReturnedMessageOperationResult handle(
            String tenantId,
            String userId,
            String requestId,
            String executionId,
            AiExecutionGateway.ExecutionResult result);
}
