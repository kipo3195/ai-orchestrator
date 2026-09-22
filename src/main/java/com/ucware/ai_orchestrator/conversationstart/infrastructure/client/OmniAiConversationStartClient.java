package com.ucware.ai_orchestrator.conversationstart.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationStartAiPort;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartContext;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartResult;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationSuggestionKind;
import com.ucware.ai_orchestrator.conversationstart.domain.SuggestionItem;

@Component
public class OmniAiConversationStartClient implements ConversationStartAiPort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String GENERATE_PATH = "/ai/v1/chat/suggestions/generate";

    private final URI generateUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OmniAiConversationStartClient(@Value("${omni-ai.base-url}") String baseUrl,
                                         ObjectMapper objectMapper) {
        this.generateUri = URI.create(baseUrl.replaceAll("/+$", "") + GENERATE_PATH);
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ConversationStartResult generateSuggestion(ConversationStartContext context) {
        if (context.suggestionKind() != ConversationSuggestionKind.TRENDING) {
            throw new UnsupportedOperationException("Only TRENDING suggestions are supported");
        }

        OmniAiRequest requestBody = new OmniAiRequest(
                context.sessionId(), context.userId(), context.roomId(),
                context.suggestionKind().name(), List.of(), new RequestOptions(5, 100, "ko"));

        try {
            HttpRequest request = HttpRequest.newBuilder(generateUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Omni AI returned HTTP " + response.statusCode());
            }

            OmniAiResponse parsed = objectMapper.readValue(response.body(), OmniAiResponse.class);
            if (parsed == null || !context.sessionId().equals(parsed.sessionId())) {
                throw new IllegalStateException("Omni AI response session does not match request");
            }
            return new ConversationStartResult(parsed.sessionId(), parsed.suggestions());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Omni AI request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Omni AI request failed", e);
        }
    }

    private record OmniAiRequest(@JsonProperty("session_id") String sessionId,
                                 @JsonProperty("user_id") String userId,
                                 @JsonProperty("room_id") String roomId,
                                 @JsonProperty("suggestion_type") String suggestionType,
                                 List<?> messages, RequestOptions options) { }

    private record RequestOptions(@JsonProperty("max_suggestions") int maxSuggestions,
                                  @JsonProperty("max_length") int maxLength,
                                  String language) { }

    private record OmniAiResponse(@JsonProperty("session_id") String sessionId,
                                  List<SuggestionItem> suggestions) { }
}
