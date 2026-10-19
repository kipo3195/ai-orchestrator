package com.ucware.ai_orchestrator.returnedmessage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleReturnedMessageExecutionResult;
import com.ucware.ai_orchestrator.returnedmessage.application.HandleUserReturned;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePolicyUnavailableException;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageOperationResult;
import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageDecision;

@WebMvcTest(value = ReturnedMessageController.class, properties = "api.version=v1")
class ReturnedMessagePolicyErrorResponseTests {

    @Autowired
    private MockMvc mockMvc;

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
                                  "tenantId": "tenant-1",
                                  "userId": "user-1",
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
}
