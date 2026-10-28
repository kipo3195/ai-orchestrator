package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageAiProperties;

class OmniAiReturnedMessageExecutionClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void startsExecutionWithCommonEnvelopeAndMapsTopicResult() throws Exception {
        RecordedRequest recorded = new RecordedRequest();
        HttpServer server = server(200, """
                {
                  "execution_id": "execution-1",
                  "task_type": "RETURNED_MESSAGE_TOPICS",
                  "contract_version": 1,
                  "status": "SUCCEEDED",
                  "result": {
                    "topics": [{
                      "topic": "제목 1",
                      "summary": "요약",
                      "priority": "NORMAL",
                      "priority_reason": null,
                      "message_count": 2,
                      "source_message_refs": ["message-1", "message-2"]
                    }],
                    "generated_at": "2026-10-28T01:00:00Z"
                  }
                }
                """, recorded);

        try {
            AiExecutionGateway.ExecutionResult result = client(server).start(command());

            assertThat(result.status()).isEqualTo(AiExecutionGateway.Status.SUCCEEDED);
            assertThat(result.result().topics()).hasSize(1);
            assertThat(result.result().topics().get(0).topic()).isEqualTo("제목 1");
            assertThat(result.result().topics().get(0).sourceMessageRefs())
                    .containsExactly("message-1", "message-2");
            assertThat(recorded.method.get()).isEqualTo("POST");
            assertThat(recorded.path.get())
                    .isEqualTo("/ai/v1/returned-message-topics/generate");
            assertThat(recorded.headers.get().getFirst("X-Tenant-Id"))
                    .isEqualTo("tenant-1");
            assertThat(recorded.headers.get().getFirst("Content-Type"))
                    .isEqualTo("application/json");

            JsonNode body = objectMapper.readTree(recorded.body.get());
            assertThat(body.get("execution_id").asText()).isEqualTo("execution-1");
            assertThat(body.get("tenant_id").asText()).isEqualTo("tenant-1");
            assertThat(body.get("task_type").asText()).isEqualTo("RETURNED_MESSAGE_TOPICS");
            assertThat(body.get("contract_version").asInt()).isEqualTo(1);
            JsonNode input = body.get("input");
            assertThat(input.get("request_id").asText()).isEqualTo("request-1");
            assertThat(input.get("user_id").asText()).isEqualTo("user-1");
            assertThat(input.get("absence_period_id").asText()).isEqualTo("period-1");
            assertThat(input.get("message_selection_ref").asText()).isEqualTo("selection-1");
            assertThat(body.has("routing_ref")).isFalse();
            assertThat(input.get("selected_items")).hasSize(2);
            assertThat(input.get("selected_items").get(0).get("source_item_key").asText())
                    .isEqualTo("message-1");
            assertThat(input.get("selected_items").get(0).get("subject").asText())
                    .isEqualTo("제목 1");
            assertThat(input.get("selected_items").get(0).get("sequence").asInt())
                    .isZero();
            assertThat(input.get("selected_items").get(1).get("source_item_key").asText())
                    .isEqualTo("message-2");
            assertThat(input.get("selected_items").get(1).get("sequence").asInt())
                    .isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reconcilesExecutionWithinTenantScopeAndMapsSucceededResponse() throws Exception {
        RecordedRequest recorded = new RecordedRequest();
        HttpServer server = server(200, """
                {
                  "execution_id": "execution-1",
                  "task_type": "RETURNED_MESSAGE_TOPICS",
                  "contract_version": 1,
                  "status": "SUCCEEDED",
                  "result": {
                    "topics": [],
                    "generated_at": "2026-10-28T01:00:00Z"
                  }
                }
                """, recorded);

        try {
            AiExecutionGateway.ExecutionResult result = client(server)
                    .reconcile("tenant-1", "execution-1");

            assertThat(result.status()).isEqualTo(AiExecutionGateway.Status.SUCCEEDED);
            assertThat(result.result().topics()).isEmpty();
            assertThat(recorded.method.get()).isEqualTo("GET");
            assertThat(recorded.path.get())
                    .isEqualTo("/ai/v1/returned-message-topics/executions/execution-1");
            assertThat(recorded.headers.get().getFirst("X-Tenant-Id"))
                    .isEqualTo("tenant-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsNotFoundWhenReconciliationConfirmsExecutionDoesNotExist() throws Exception {
        HttpServer server = server(404, "", new RecordedRequest());

        try {
            assertThat(client(server).reconcile("tenant-1", "execution-1"))
                    .isEqualTo(new AiExecutionGateway.ExecutionResult(
                            AiExecutionGateway.Status.NOT_FOUND, null, null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsExplicitStartValidationRejectionToSafeFailure() throws Exception {
        HttpServer server = server(422, """
                {"detail":"internal validation information"}
                """, new RecordedRequest());

        try {
            assertThat(client(server).start(command()))
                    .isEqualTo(new AiExecutionGateway.ExecutionResult(
                            AiExecutionGateway.Status.FAILED,
                            null,
                            "AI_EXECUTION_REQUEST_INVALID"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsImmutableRequestConflictToTerminalFailure() throws Exception {
        HttpServer server = server(409, """
                {"detail":"request fingerprint does not match"}
                """, new RecordedRequest());

        try {
            assertThat(client(server).start(command()))
                    .isEqualTo(new AiExecutionGateway.ExecutionResult(
                            AiExecutionGateway.Status.FAILED,
                            null,
                            "AI_EXECUTION_CONFLICT"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsServerFailureUncertainForLaterReconciliation() throws Exception {
        HttpServer server = server(503, "", new RecordedRequest());

        try {
            assertThatThrownBy(() -> client(server).start(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Omni AI Returned Message start returned HTTP 503");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsResponseForAnotherExecution() throws Exception {
        HttpServer server = server(202, """
                {
                  "execution_id": "another-execution",
                  "task_type": "RETURNED_MESSAGE_TOPICS",
                  "contract_version": 1,
                  "status": "ACCEPTED"
                }
                """, new RecordedRequest());

        try {
            assertThatThrownBy(() -> client(server).start(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Omni AI Returned Message response does not match execution");
        } finally {
            server.stop(0);
        }
    }

    private OmniAiReturnedMessageExecutionClient client(HttpServer server) {
        ReturnedMessageAiProperties properties = new ReturnedMessageAiProperties(
                URI.create("http://localhost:" + server.getAddress().getPort()),
                "/ai/v1/returned-message-topics/generate",
                "/ai/v1/returned-message-topics/executions/{executionId}",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
        return new OmniAiReturnedMessageExecutionClient(properties, objectMapper);
    }

    private AiExecutionGateway.ExecutionCommand command() {
        return new AiExecutionGateway.ExecutionCommand(
                "request-1",
                "trigger-1",
                "execution-1",
                "tenant-1",
                "user-1",
                "period-1",
                "selection-1",
                List.of(
                        new ReturnedMessageSelection.Item(
                                "WEBSOCKET_SERVICE", "MESSAGE", "message-1", "제목 1", 0),
                        new ReturnedMessageSelection.Item(
                                "WEBSOCKET_SERVICE", "MESSAGE", "message-2", null, 1)),
                null);
    }

    private HttpServer server(int status, String responseBody, RecordedRequest recorded)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> respond(
                exchange, status, responseBody, recorded));
        server.start();
        return server;
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String responseBody,
            RecordedRequest recorded) throws IOException {
        recorded.method.set(exchange.getRequestMethod());
        recorded.path.set(exchange.getRequestURI().getRawPath());
        recorded.headers.set(exchange.getRequestHeaders());
        recorded.body.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static final class RecordedRequest {
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<Headers> headers =
                new AtomicReference<>(new Headers());
        private final AtomicReference<String> body = new AtomicReference<>();
    }
}
