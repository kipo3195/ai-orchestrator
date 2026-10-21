package com.ucware.ai_orchestrator.returnedmessage.application.port;

import java.util.List;

import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;

public interface AiExecutionGateway {

    ExecutionResult start(ExecutionCommand command);

    ExecutionResult reconcile(String tenantId, String executionId);

    record ExecutionCommand(
            String requestId,
            String triggerId,
            String executionId,
            String tenantId,
            String userId,
            String absencePeriodId,
            String messageSelectionRef,
            List<ReturnedMessageSelection.Item> selectedItems,
            RoutingRef routingRef) {

        public ExecutionCommand {
            selectedItems = List.copyOf(selectedItems);
        }
    }

    record ExecutionResult(
            Status status,
            String resultRef,
            String safeFailureReason) {

        public static ExecutionResult accepted() {
            return new ExecutionResult(Status.ACCEPTED, null, null);
        }
    }

    enum Status {
        ACCEPTED,
        RUNNING,
        UNKNOWN,
        NOT_FOUND,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
