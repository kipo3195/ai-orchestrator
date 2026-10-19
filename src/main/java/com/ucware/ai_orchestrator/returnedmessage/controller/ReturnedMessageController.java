package com.ucware.ai_orchestrator.returnedmessage.controller;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ucware.ai_orchestrator.result.domain.RoutingRef;
import com.ucware.ai_orchestrator.result.domain.RoutingScope;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageExecutionResult;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleUserReturned;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageOperationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageDecision;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;

@RestController
@RequestMapping("/api/${api.version}/returned-messages")
public class ReturnedMessageController {

    private final HandleUserReturned handleUserReturned;
    private final HandleReturnedMessageConfirmation handleConfirmation;
    private final HandleReturnedMessageExecutionResult handleExecutionResult;

    public ReturnedMessageController(
            HandleUserReturned handleUserReturned,
            HandleReturnedMessageConfirmation handleConfirmation,
            HandleReturnedMessageExecutionResult handleExecutionResult) {
        this.handleUserReturned = handleUserReturned;
        this.handleConfirmation = handleConfirmation;
        this.handleExecutionResult = handleExecutionResult;
    }

    @PostMapping
    public ResponseEntity<ReturnedMessageResponse> userReturned(
            @Valid @RequestBody UserReturnedHttpRequest request) {
        requireConsistentRoutingOwner(request.tenantId(), request.userId(), request.routingRef());

        RoutingRef routingRef = RoutingRef.forClientSession(
                request.tenantId(),
                request.userId(),
                request.routingRef().targetRef(),
                new RoutingScope("ABSENCE_PERIOD", request.absencePeriodId()),
                request.routingRef().connectionType());
        ReturnedMessageOperationResult result = handleUserReturned.handle(
                new ReturnedMessageRequest(
                        request.requestId(),
                        request.triggerId(),
                        request.tenantId(),
                        request.userId(),
                        request.absencePeriodId(),
                        request.offlineSince(),
                        request.returnedAt(),
                        request.occurredAt(),
                        request.expiresAt(),
                        routingRef));
        return ResponseEntity.accepted().body(ReturnedMessageResponse.from(result));
    }

    @PostMapping("/{requestId}/confirmations")
    public ResponseEntity<ReturnedMessageResponse> confirm(
            @PathVariable String requestId,
            @Valid @RequestBody ConfirmationHttpRequest request) {
        RoutingRefHttpRequest routingRef = request.routingRef();
        ReturnedMessageOperationResult result = handleConfirmation.handle(
                new ReturnedMessageConfirmation(
                        routingRef.tenantId(),
                        routingRef.userId(),
                        requestId,
                        request.absencePeriodId(),
                        request.confirmationId(),
                        request.messageSelectionRef(),
                        request.decision(),
                        request.decidedAt(),
                        routingRef.targetRef(),
                        routingRef.connectionType()));
        return ResponseEntity.ok(ReturnedMessageResponse.from(result));
    }

    @PostMapping("/{requestId}/execution-results")
    public ResponseEntity<ReturnedMessageResponse> executionResult(
            @PathVariable String requestId,
            @Valid @RequestBody ExecutionResultHttpRequest request) {
        ReturnedMessageOperationResult result = handleExecutionResult.handle(
                request.tenantId(),
                request.userId(),
                requestId,
                request.executionId(),
                new AiExecutionGateway.ExecutionResult(
                        request.status(), request.resultRef(), request.failureReason()));
        return ResponseEntity.ok(ReturnedMessageResponse.from(result));
    }

    private static void requireConsistentRoutingOwner(
            String requestTenantId,
            String requestUserId,
            RoutingRefHttpRequest routingRef) {
        if (!requestTenantId.equals(routingRef.tenantId())
                || !requestUserId.equals(routingRef.userId())) {
            throw new IllegalArgumentException("Routing reference owner does not match request owner");
        }
    }

    public record UserReturnedHttpRequest(
            @NotBlank String requestId,
            @NotBlank String triggerId,
            @NotBlank String tenantId,
            @NotBlank String userId,
            @NotBlank String absencePeriodId,
            @NotNull Instant offlineSince,
            @NotNull Instant returnedAt,
            @NotNull Instant occurredAt,
            @NotNull Instant expiresAt,
            @NotNull @Valid RoutingRefHttpRequest routingRef) {
    }

    public record RoutingRefHttpRequest(
            @NotBlank String tenantId,
            @NotBlank String userId,
            @NotBlank String targetRef,
            @Positive int connectionType) {
    }

    public record ConfirmationHttpRequest(
            @NotBlank String absencePeriodId,
            @NotBlank String confirmationId,
            @NotBlank String messageSelectionRef,
            @NotNull ReturnedMessageDecision decision,
            @NotNull Instant decidedAt,
            @NotNull @Valid RoutingRefHttpRequest routingRef) {
    }

    public record ExecutionResultHttpRequest(
            @NotBlank String tenantId,
            @NotBlank String userId,
            @NotBlank String executionId,
            @NotNull AiExecutionGateway.Status status,
            String resultRef,
            String failureReason) {
    }

    public record ReturnedMessageResponse(
            ReturnedMessageOperationResult.Outcome outcome,
            String reason,
            String requestId,
            String status,
            Integer unreadCount,
            Instant confirmationExpiresAt,
            String executionId) {

        static ReturnedMessageResponse from(ReturnedMessageOperationResult result) {
            ReturnedMessageLifecycle lifecycle = result.lifecycle();
            return new ReturnedMessageResponse(
                    result.outcome(),
                    result.reason(),
                    lifecycle == null ? null : lifecycle.requestId(),
                    lifecycle == null ? null : lifecycle.status().name(),
                    lifecycle == null ? null : lifecycle.unreadCount(),
                    lifecycle == null ? null : lifecycle.confirmationExpiresAt(),
                    lifecycle == null ? null : lifecycle.executionId());
        }
    }
}
