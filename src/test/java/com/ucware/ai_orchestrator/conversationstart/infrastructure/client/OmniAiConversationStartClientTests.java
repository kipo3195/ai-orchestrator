package com.ucware.ai_orchestrator.conversationstart.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartContext;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationSuggestionKind;
import com.ucware.ai_orchestrator.global.infrastructure.http.ServiceApiKeyProperties;

class OmniAiConversationStartClientTests {

    @Test
    void sendsConfiguredAiKeyWithTheExistingRequestContract() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/v1/chat/suggestions/generate", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-Test-AI-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"session_id\":\"execution-1\",\"suggestions\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            OmniAiConversationStartClient client = new OmniAiConversationStartClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), mapper,
                    new ServiceApiKeyProperties(null,
                            new ServiceApiKeyProperties.ApiKey("X-Test-AI-Key", "ai-test-key")));
            var result = client.generateSuggestion(new ConversationStartContext(
                    "execution-1", "session-1", "user-1", "room-1", Instant.now(),
                    ConversationSuggestionKind.TRENDING, List.of()));
            assertThat(key.get()).isEqualTo("ai-test-key");
            assertThat(mapper.readTree(body.get()).get("session_id").asText()).isEqualTo("execution-1");
            assertThat(mapper.readTree(body.get()).get("suggestion_type").asText()).isEqualTo("TRENDING");
            assertThat(result.executionId()).isEqualTo("execution-1");
            assertThat(result.suggestions()).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
