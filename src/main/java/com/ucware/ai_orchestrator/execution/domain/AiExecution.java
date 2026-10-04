package com.ucware.ai_orchestrator.execution.domain;

import java.time.Instant;
import java.util.Objects;

import com.ucware.ai_orchestrator.result.domain.RoutingRef;

public class AiExecution {

    private final String executionId;
    private final RoutingRef routingRef;
    private final AiWorkflowType workflowType;
    private final Instant requestedAt;
    private final Instant scheduledFor;
    private volatile AiExecutionStatus status;

    private AiExecution(String executionId, RoutingRef routingRef, AiWorkflowType workflowType,
                        Instant requestedAt, Instant scheduledFor) {
        this.executionId = Objects.requireNonNull(executionId);
        this.routingRef = Objects.requireNonNull(routingRef);
        this.workflowType = Objects.requireNonNull(workflowType);
        this.requestedAt = Objects.requireNonNull(requestedAt);
        this.scheduledFor = scheduledFor;
        this.status = AiExecutionStatus.CREATED;
    }

    public static AiExecution create(String executionId, AiWorkflowType workflowType, RoutingRef routingRef,
                                     Instant requestedAt, Instant scheduledFor) {
        return new AiExecution(executionId, routingRef, workflowType, requestedAt, scheduledFor);
    }

    public synchronized void schedule() {
        if (status != AiExecutionStatus.CREATED) {
            throw new IllegalStateException("Only created executions can be scheduled");
        }
        status = AiExecutionStatus.SCHEDULED;
    }

    public synchronized void start() {
        if (status != AiExecutionStatus.SCHEDULED) {
            throw new IllegalStateException("Only scheduled executions can start");
        }
        status = AiExecutionStatus.EXECUTING;
    }

    public synchronized void complete() {
        if (status != AiExecutionStatus.EXECUTING) {
            throw new IllegalStateException("Only executing executions can complete");
        }
        status = AiExecutionStatus.COMPLETED;
    }

    public synchronized boolean cancel() {
        if (!isActive()) {
            return false;
        }
        status = AiExecutionStatus.CANCELLED;
        return true;
    }

    public synchronized void fail() {
        if (status == AiExecutionStatus.SCHEDULED || status == AiExecutionStatus.EXECUTING) {
            status = AiExecutionStatus.FAILED;
        }
    }

    public boolean isScheduled() {
        return status == AiExecutionStatus.SCHEDULED;
    }

    public boolean isActive() {
        return status == AiExecutionStatus.CREATED
                || status == AiExecutionStatus.SCHEDULED
                || status == AiExecutionStatus.EXECUTING;
    }

    public String getExecutionId() { return executionId; }
    public String getClientSessionId() { return routingRef.clientSessionId(); }
    public RoutingRef getRoutingRef() { return routingRef; }
    public AiWorkflowType getWorkflowType() { return workflowType; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getScheduledFor() { return scheduledFor; }
    public AiExecutionStatus getStatus() { return status; }
}
