package com.ucware.ai_orchestrator.conversationstart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ucware.ai_orchestrator.conversationstart.application.CancelConversationSuggestionUseCase;
import com.ucware.ai_orchestrator.conversationstart.application.StartConversationSuggestionUseCase;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.execution.domain.AiExecution;
import com.ucware.ai_orchestrator.execution.domain.AiWorkflowType;
import com.ucware.ai_orchestrator.result.domain.RoutingRef;

@WebMvcTest(value = ConversationStartController.class, properties = "api.version=v2")
class ConversationStartVersionTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartConversationSuggestionUseCase startUseCase;

    @MockitoBean
    private CancelConversationSuggestionUseCase cancelUseCase;

    @Test
    void configuredVersionControlsRequestPathAndLocation() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AiExecution aiExecution = AiExecution.create(
                "execution-1", AiWorkflowType.CONVERSATION_START,
                RoutingRef.forClientSession("default", "user-1", "client-session-1", null),
                now, now);
        aiExecution.schedule();
        when(startUseCase.start(any(), any(), any(), any(), any())).thenReturn(ConversationStart.create(
                aiExecution, "room-session-1", "user-1", "room-1", "group"));

        mockMvc.perform(post("/api/v2/conversation-starts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomSessionId\":\"room-session-1\"," +
                                "\"clientSessionId\":\"client-session-1\",\"userID\":\"user-1\"," +
                                "\"roomKey\":\"room-1\",\"chatType\":\"group\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "/api/v2/conversation-starts/executions/execution-1"))
                .andExpect(jsonPath("$.executionId").value("execution-1"))
                .andExpect(jsonPath("$.workflowType").value("CONVERSATION_START"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.requestedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.scheduledFor").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.roomSessionId").value("room-session-1"))
                .andExpect(jsonPath("$.data.roomKey").value("room-1"))
                .andExpect(jsonPath("$.data.chatType").value("group"));
    }
}
