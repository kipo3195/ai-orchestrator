package com.ucware.ai_orchestrator.returnedmessage.controller;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageExecutionResult;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleUserReturned;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageOperationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageDecision;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.routing.controller.RoutingRefRequest;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

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
        requireConnectionType(request.routingRef());
        RoutingRef routingRef = request.routingRef().toRoutingRef(
                new RoutingScope("ABSENCE_PERIOD", request.absencePeriodId()));
        ReturnedMessageOperationResult result = handleUserReturned.handle(
                new ReturnedMessageRequest(
                        request.requestId(),
                        request.triggerId(),
                        routingRef.tenantId(),
                        routingRef.userId(),
                        request.absencePeriodId(),
                        request.offlineSince(),
                        request.returnedAt(),
                        request.occurredAt(),
                        request.expiresAt(),
                        routingRef));
        return ResponseEntity.ok(ReturnedMessageResponse.from(
                result, request.requestId(), request.triggerId()));
    }

    @PostMapping("/{requestId}/confirmations")
    public ResponseEntity<ReturnedMessageResponse> confirm(
            @PathVariable String requestId,
            @Valid @RequestBody ConfirmationHttpRequest request) {
        RoutingRefRequest routingRef = request.routingRef();
        int connectionType = requireConnectionType(routingRef);
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
                        connectionType));
        return ResponseEntity.ok(ReturnedMessageResponse.from(result));
    }

    private static int requireConnectionType(RoutingRefRequest routingRef) {
        if (routingRef.connectionType() == null) {
            throw new IllegalArgumentException(
                    "Returned Message routing reference requires connectionType");
        }
        return routingRef.connectionType();
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

    // 클래스 내부에 선언한 record는 암묵적으로 static이기 때문에 외부 Controller 인스턴스를 참조하지 않음
    public record UserReturnedHttpRequest(
            @NotBlank String requestId,
            @NotBlank String triggerId,
            @NotBlank String absencePeriodId,
            @NotNull Instant offlineSince,
            @NotNull Instant returnedAt,
            @NotNull Instant occurredAt,
            @NotNull Instant expiresAt,
            @NotNull @Valid RoutingRefRequest routingRef) {
    }

    public record ConfirmationHttpRequest(
            @NotBlank String absencePeriodId,
            @NotBlank String confirmationId,
            @NotBlank String messageSelectionRef,
            @NotNull ReturnedMessageDecision decision,
            @NotNull Instant decidedAt,
            @NotNull @Valid RoutingRefRequest routingRef) {
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
            String requestId,
            String triggerId,
            ReturnedMessageOperationResult.Outcome outcome,
            String reason,
            String status,
            Integer unreadCount,
            Instant confirmationExpiresAt,
            String executionId) {

        // ReturnedMessageResponse를 new로 생성하기 전에 ReturnedMessageResponse.from을 호출하게 하는
        // 정적 팩토리 함수
        static ReturnedMessageResponse from(ReturnedMessageOperationResult result) {
            ReturnedMessageLifecycle lifecycle = result.lifecycle();
            return from(
                    result,
                    lifecycle == null ? null : lifecycle.requestId(),
                    lifecycle == null ? null : lifecycle.triggerId());
        }

        static ReturnedMessageResponse from(
                ReturnedMessageOperationResult result,
                String requestId,
                String triggerId) {
            ReturnedMessageLifecycle lifecycle = result.lifecycle();
            return new ReturnedMessageResponse(
                    requestId,
                    triggerId,
                    result.outcome(),
                    result.reason(),
                    lifecycle == null ? null : lifecycle.status().name(),
                    lifecycle == null ? null : lifecycle.unreadCount(),
                    lifecycle == null ? null : lifecycle.confirmationExpiresAt(),
                    lifecycle == null ? null : lifecycle.executionId());
        }
    }
}
