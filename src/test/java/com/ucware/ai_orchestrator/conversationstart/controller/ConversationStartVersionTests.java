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
        when(startUseCase.start(any(), any(), any())).thenReturn(
                ConversationStart.create("session-1", "user-1", "room-1", "group", now, now));

        mockMvc.perform(post("/api/v2/conversation-starts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userID\":\"user-1\",\"roomKey\":\"room-1\",\"chatType\":\"group\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v2/conversation-starts/session-1"))
                .andExpect(jsonPath("$.sessionId").value("session-1"));
    }
}
