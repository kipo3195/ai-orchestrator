package com.ucware.ai_orchestrator.returnedmessage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
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
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageExecutionResult;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleUserReturned;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePolicyUnavailableException;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageOperationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageDecision;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;
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
    void executionResultOwnerIsReadFromRequestBody() throws Exception {
        when(handleExecutionResult.handle(any(), any(), any(), any(), any()))
                .thenReturn(ReturnedMessageOperationResult.rejected("test"));

        mockMvc.perform(post("/api/v1/returned-messages/request-1/execution-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "tenant-1",
                                  "userId": "user-1",
                                  "executionId": "execution-1",
                                  "status": "SUCCEEDED",
                                  "resultRef": "result-1"
                                }
                                """))
                .andExpect(status().isOk());

        verify(handleExecutionResult).handle(
                "tenant-1",
                "user-1",
                "request-1",
                "execution-1",
                new AiExecutionGateway.ExecutionResult(
                        AiExecutionGateway.Status.SUCCEEDED, "result-1", null));
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
