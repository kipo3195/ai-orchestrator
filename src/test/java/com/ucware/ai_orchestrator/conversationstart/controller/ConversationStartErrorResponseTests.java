package com.ucware.ai_orchestrator.conversationstart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;


import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ucware.ai_orchestrator.conversationstart.application.CancelConversationSuggestionUseCase;
import com.ucware.ai_orchestrator.conversationstart.application.StartConversationSuggestionUseCase;

@WebMvcTest(value = ConversationStartController.class, properties = "api.version=v1")
class ConversationStartErrorResponseTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartConversationSuggestionUseCase startUseCase;

    @MockitoBean
    private CancelConversationSuggestionUseCase cancelUseCase;

    @Test
    void invalidRequestUsesProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/conversation-starts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomSessionId\":\"room-session-1\",\"userID\":\"\"," +
                                "\"roomKey\":\"room-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("userID: must not be blank"));
    }

    @Test
    void malformedJsonDoesNotExposeParserDetails() throws Exception {
        mockMvc.perform(post("/api/v1/conversation-starts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid request"));
    }

    @Test
    void missingRoomSessionIdUsesProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/conversation-starts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userID\":\"user-1\",\"roomKey\":\"room-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("roomSessionId: must not be blank"));
    }

    @Test
    void leaveRoomCancelsByRoomSessionId() throws Exception {
        when(cancelUseCase.cancel("room-session-1", "user-1", "room-1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/conversation-starts/room-session-1")
                        .param("userID", "user-1")
                        .param("roomKey", "room-1"))
                .andExpect(status().isNoContent());

        verify(cancelUseCase).cancel("room-session-1", "user-1", "room-1");
    }

    @Test
    void missingActiveConversationUsesNotFoundProblem() throws Exception {
        mockMvc.perform(delete("/api/v1/conversation-starts/room-session-1")
                        .param("userID", "user-1")
                        .param("roomKey", "room-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Active conversation start not found"));
    }

    @Test
    void unavailableConversationDoesNotExposeExceptionMessage() throws Exception {
        when(startUseCase.start(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("internal state"));

        mockMvc.perform(post("/api/v1/conversation-starts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomSessionId\":\"room-session-1\",\"userID\":\"user-1\"," +
                                "\"roomKey\":\"room-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Conversation start is unavailable"));
    }
}
