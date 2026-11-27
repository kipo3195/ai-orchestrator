package com.ucware.ai_orchestrator.returnedmessage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ucware.ai_orchestrator.result.domain.LifecycleEvent;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageExecutionResult;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleUserReturned;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePolicyUnavailableException;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageOperationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageDecision;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageLifecycle;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageStatus;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

@WebMvcTest(value = ReturnedMessageController.class, properties = "api.version=v1")
class ReturnedMessagePolicyErrorResponseTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HandleUserReturned handleUserReturned;

    @MockitoBean
    private HandleReturnedMessageConfirmation handleConfirmation;

    @MockitoBean
    private HandleReturnedMessageExecutionResult handleExecutionResult;

    @Test
    void unavailablePolicyUsesServiceUnavailableProblemDetails() throws Exception {
        when(handleUserReturned.handle(any()))
                .thenThrow(new ReturnedMessagePolicyUnavailableException(
                        "POLICY_GLOBAL_POLICY_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "request-1",
                                  "triggerId": "trigger-1",
                                  "absencePeriodId": "period-1",
                                  "offlineSince": "2026-10-19T05:30:00Z",
                                  "returnedAt": "2026-10-19T06:00:00Z",
                                  "occurredAt": "2026-10-19T06:00:00Z",
                                  "expiresAt": "2026-10-19T06:05:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1",
                                    "connectionType": 1
                                  }
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(
                        "Returned Message policy is unavailable"));
    }

    @Test
    void userReturnedOwnerIsReadFromRoutingRef() throws Exception {
        when(handleUserReturned.handle(any()))
                .thenReturn(ReturnedMessageOperationResult.rejected("test"));

        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "request-1",
                                  "triggerId": "trigger-1",
                                  "absencePeriodId": "period-1",
                                  "offlineSince": "2026-10-19T05:30:00Z",
                                  "returnedAt": "2026-10-19T06:00:00Z",
                                  "occurredAt": "2026-10-19T06:00:00Z",
                                  "expiresAt": "2026-10-19T06:05:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1",
                                    "connectionType": 1
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("request-1"))
                .andExpect(jsonPath("$.triggerId").value("trigger-1"))
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("test"))
                .andExpect(jsonPath("$.status").value(nullValue()));

        ArgumentCaptor<ReturnedMessageRequest> requestCaptor =
                ArgumentCaptor.forClass(ReturnedMessageRequest.class);
        verify(handleUserReturned).handle(requestCaptor.capture());
        ReturnedMessageRequest request = requestCaptor.getValue();
        assertThat(request.tenantId()).isEqualTo("tenant-1");
        assertThat(request.userId()).isEqualTo("user-1");
        assertThat(request.routingRef().clientSessionId())
                .isEqualTo("target-1");
        assertThat(request.routingRef().connectionType()).isEqualTo(1);
        assertThat(request.routingRef().scope())
                .isEqualTo(new RoutingScope("ABSENCE_PERIOD", "period-1"));
    }

    @Test
    void userReturnedRequiresConnectionType() throws Exception {
        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "request-1",
                                  "triggerId": "trigger-1",
                                  "absencePeriodId": "period-1",
                                  "offlineSince": "2026-10-19T05:30:00Z",
                                  "returnedAt": "2026-10-19T06:00:00Z",
                                  "occurredAt": "2026-10-19T06:00:00Z",
                                  "expiresAt": "2026-10-19T06:05:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid request"));

        verifyNoInteractions(handleUserReturned);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void userReturnedRejectsNonPositiveConnectionType(int connectionType) throws Exception {
        ObjectNode body = validUserReturnedBody();
        ((ObjectNode) body.get("routingRef")).put("connectionType", connectionType);

        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());

        verifyNoInteractions(handleUserReturned);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requestId", "triggerId", "absencePeriodId",
            "offlineSince", "returnedAt", "occurredAt", "expiresAt"
    })
    void userReturnedRejectsMissingRequiredField(String field) throws Exception {
        ObjectNode body = validUserReturnedBody();
        body.remove(field);

        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());

        verifyNoInteractions(handleUserReturned);
    }

    @Test
    void sourceTimeContractViolationUsesBadRequestProblemDetails() throws Exception {
        when(handleUserReturned.handle(any()))
                .thenThrow(new IllegalArgumentException(
                        "Invalid Returned Message source time contract"));
        ObjectNode body = validUserReturnedBody();
        body.put("offlineSince", "2026-10-19T06:00:00Z");

        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid request"));

        ArgumentCaptor<ReturnedMessageRequest> requestCaptor =
                ArgumentCaptor.forClass(ReturnedMessageRequest.class);
        verify(handleUserReturned).handle(requestCaptor.capture());
        assertThat(requestCaptor.getValue().offlineSince())
                .isEqualTo(requestCaptor.getValue().returnedAt());
    }

    @Test
    void confirmationCorrelationIsReadFromRequestBody() throws Exception {
        when(handleConfirmation.handle(any()))
                .thenReturn(ReturnedMessageOperationResult.rejected("test"));

        mockMvc.perform(post("/api/v1/returned-messages/request-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "absencePeriodId": "period-1",
                                  "confirmationId": "confirmation-1",
                                  "messageSelectionRef": "selection-1",
                                  "decision": "YES",
                                  "decidedAt": "2026-10-19T06:01:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1",
                                    "connectionType": 1
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        verify(handleConfirmation).handle(new ReturnedMessageConfirmation(
                "tenant-1",
                "user-1",
                "request-1",
                "period-1",
                "confirmation-1",
                "selection-1",
                ReturnedMessageDecision.YES,
                Instant.parse("2026-10-19T06:01:00Z"),
                "target-1",
                1));
    }

    @Test
    void awaitingConfirmationResponseIncludesConfirmationCorrelation() throws Exception {
        ReturnedMessageLifecycle awaiting = mock(ReturnedMessageLifecycle.class);
        LifecycleEvent lifecycleEvent = new LifecycleEvent(
                "returned_message_topic",
                "request-1",
                "client-event-1",
                1,
                "AWAITING_CONFIRMATION",
                Instant.parse("2026-10-19T06:00:01Z"));
        when(awaiting.status()).thenReturn(ReturnedMessageStatus.AWAITING_CONFIRMATION);
        when(awaiting.requestId()).thenReturn("request-1");
        when(awaiting.triggerId()).thenReturn("trigger-1");
        when(awaiting.confirmationId()).thenReturn("confirmation-1");
        when(awaiting.messageSelectionRef()).thenReturn("selection-1");
        when(awaiting.lifecycleEvent()).thenReturn(lifecycleEvent);
        when(handleUserReturned.handle(any()))
                .thenReturn(ReturnedMessageOperationResult.created(awaiting));

        mockMvc.perform(post("/api/v1/returned-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUserReturnedBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("request-1"))
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.confirmationId").value("confirmation-1"))
                .andExpect(jsonPath("$.messageSelectionRef").value("selection-1"))
                .andExpect(jsonPath("$.lifecycleEvent.type").value("returned_message_topic"))
                .andExpect(jsonPath("$.lifecycleEvent.lifecycleId").value("request-1"))
                .andExpect(jsonPath("$.lifecycleEvent.eventId").value("client-event-1"))
                .andExpect(jsonPath("$.lifecycleEvent.revision").value(1))
                .andExpect(jsonPath("$.lifecycleEvent.state").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.lifecycleEvent.occurredAt")
                        .value("2026-10-19T06:00:01Z"));
    }

    @Test
    void acceptedConfirmationReturns202WhileAiRunsInBackground() throws Exception {
        ReturnedMessageLifecycle processing = mock(ReturnedMessageLifecycle.class);
        LifecycleEvent lifecycleEvent = new LifecycleEvent(
                "returned_message_topic",
                "request-1",
                "client-event-2",
                2,
                "PROCESSING",
                Instant.parse("2026-10-19T06:01:00Z"));
        when(processing.status()).thenReturn(ReturnedMessageStatus.PROCESSING);
        when(processing.requestId()).thenReturn("request-1");
        when(processing.triggerId()).thenReturn("trigger-1");
        when(processing.executionId()).thenReturn("execution-1");
        when(processing.lifecycleEvent()).thenReturn(lifecycleEvent);
        when(handleConfirmation.handle(any()))
                .thenReturn(ReturnedMessageOperationResult.updated(processing));

        mockMvc.perform(post("/api/v1/returned-messages/request-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "absencePeriodId": "period-1",
                                  "confirmationId": "confirmation-1",
                                  "messageSelectionRef": "selection-1",
                                  "decision": "YES",
                                  "decidedAt": "2026-10-19T06:01:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1",
                                    "connectionType": 1
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.executionId").value("execution-1"))
                .andExpect(jsonPath("$.lifecycleEvent.eventId").value("client-event-2"))
                .andExpect(jsonPath("$.lifecycleEvent.revision").value(2))
                .andExpect(jsonPath("$.lifecycleEvent.state").value("PROCESSING"));
    }

    @Test
    void declinedConfirmationReturnsDeclinedLifecycleEvent() throws Exception {
        ReturnedMessageLifecycle declined = mock(ReturnedMessageLifecycle.class);
        when(declined.status()).thenReturn(ReturnedMessageStatus.DECLINED);
        when(declined.requestId()).thenReturn("request-1");
        when(declined.triggerId()).thenReturn("trigger-1");
        when(declined.lifecycleEvent()).thenReturn(new LifecycleEvent(
                "returned_message_topic",
                "request-1",
                "client-event-2",
                2,
                "DECLINED",
                Instant.parse("2026-10-19T06:01:00Z")));
        when(handleConfirmation.handle(any()))
                .thenReturn(ReturnedMessageOperationResult.updated(declined));

        mockMvc.perform(post("/api/v1/returned-messages/request-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "absencePeriodId": "period-1",
                                  "confirmationId": "confirmation-1",
                                  "messageSelectionRef": "selection-1",
                                  "decision": "NO",
                                  "decidedAt": "2026-10-19T06:01:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1",
                                    "connectionType": 1
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.lifecycleEvent.eventId").value("client-event-2"))
                .andExpect(jsonPath("$.lifecycleEvent.revision").value(2))
                .andExpect(jsonPath("$.lifecycleEvent.state").value("DECLINED"));
    }

    @Test
    void confirmationRequiresConnectionType() throws Exception {
        mockMvc.perform(post("/api/v1/returned-messages/request-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "absencePeriodId": "period-1",
                                  "confirmationId": "confirmation-1",
                                  "messageSelectionRef": "selection-1",
                                  "decision": "YES",
                                  "decidedAt": "2026-10-19T06:01:00Z",
                                  "routingRef": {
                                    "tenantId": "tenant-1",
                                    "userId": "user-1",
                                    "targetRef": "target-1"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid request"));
    }

    @Test
    void executionResultAcceptsNestedMessagesAndPreservesReasons() throws Exception {
        when(handleExecutionResult.handle(any(), any(), any(), any(), any()))
                .thenReturn(ReturnedMessageOperationResult.rejected("test"));
        mockMvc.perform(post("/api/v1/returned-messages/request-1/execution-results")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "event-1", "tenantId": "tenant-1", "userId": "user-1",
                                  "executionId": "execution-1", "taskType": "RETURNED_MESSAGE_TOPICS",
                                  "contractVersion": 2, "status": "SUCCEEDED",
                                  "result": {
                                    "topics": [{"topic": "1123", "summary": "제목만 제공됨",
                                      "messages": [{"messageRef": "message-1", "subject": "1123",
                                        "priority": "UNKNOWN", "priorityReason": "판단 불가",
                                        "replyRequired": false, "replyReason": "명시적 회신 요청 없음",
                                        "actionRequired": true, "actionReason": "확인 요청"}]}],
                                    "generatedAt": "2026-11-27T01:00:00Z"
                                  }
                                }
                                """))
                .andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(AiExecutionGateway.ExecutionResult.class);
        verify(handleExecutionResult).handle(
                org.mockito.ArgumentMatchers.eq("tenant-1"), org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("request-1"), org.mockito.ArgumentMatchers.eq("execution-1"),
                captor.capture());
        var message = captor.getValue().result().messageAssessments().get(0);
        org.assertj.core.api.Assertions.assertThat(message.subject()).isEqualTo("1123");
        org.assertj.core.api.Assertions.assertThat(message.priorityReason()).isEqualTo("판단 불가");
        org.assertj.core.api.Assertions.assertThat(message.actionRequired()).isTrue();
    }

    @Test
    void executionResultAcceptsMatchingTenantScope() throws Exception {
        when(handleExecutionResult.handle(any(), any(), any(), any(), any()))
                .thenReturn(ReturnedMessageOperationResult.rejected("test"));

        mockMvc.perform(post("/api/v1/returned-messages/request-1/execution-results")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "event-1",
                                  "tenantId": "tenant-1",
                                  "userId": "user-1",
                                  "executionId": "execution-1",
                                  "taskType": "RETURNED_MESSAGE_TOPICS",
                                  "contractVersion": 2,
                                  "status": "FAILED",
                                  "failureReason": "AI_EXECUTION_FAILED"
                                }
                                """))
                .andExpect(status().isOk());

        verify(handleExecutionResult).handle(
                "tenant-1",
                "user-1",
                "request-1",
                "execution-1",
                new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.FAILED, null, "AI_EXECUTION_FAILED"));
    }

    @Test
    void executionResultRejectsUnsupportedContractWithProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/returned-messages/request-1/execution-results")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "event-1",
                                  "tenantId": "tenant-1",
                                  "userId": "user-1",
                                  "executionId": "execution-1",
                                  "taskType": "RETURNED_MESSAGE_TOPICS",
                                  "contractVersion": 1,
                                  "status": "FAILED",
                                  "failureReason": "AI_EXECUTION_FAILED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid request"));

        verifyNoInteractions(handleExecutionResult);
    }

    @Test
    void executionResultRejectsMismatchedTenantScopeWithProblemDetails()
            throws Exception {
        mockMvc.perform(post("/api/v1/returned-messages/request-1/execution-results")
                        .header("X-Tenant-Id", "another-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "event-1",
                                  "tenantId": "tenant-1",
                                  "userId": "user-1",
                                  "executionId": "execution-1",
                                  "taskType": "RETURNED_MESSAGE_TOPICS",
                                  "contractVersion": 2,
                                  "status": "FAILED",
                                  "failureReason": "AI_EXECUTION_FAILED"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(
                        "Returned Message tenant scope is not allowed"));

        verifyNoInteractions(handleExecutionResult);
    }

    private ObjectNode validUserReturnedBody() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "requestId": "request-1",
                  "triggerId": "trigger-1",
                  "absencePeriodId": "period-1",
                  "offlineSince": "2026-10-19T05:30:00Z",
                  "returnedAt": "2026-10-19T06:00:00Z",
                  "occurredAt": "2026-10-19T06:00:00Z",
                  "expiresAt": "2026-10-19T06:05:00Z",
                  "routingRef": {
                    "tenantId": "tenant-1",
                    "userId": "user-1",
                    "targetRef": "target-1",
                    "connectionType": 1
                  }
                }
                """);
    }
}
