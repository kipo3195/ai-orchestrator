package com.ucware.ai_orchestrator.result.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.domain.LifecycleEvent;
import com.ucware.ai_orchestrator.result.infrastructure.config.ConfiguredRealtimeInstanceEndpointResolver;
import com.ucware.ai_orchestrator.result.infrastructure.config.ResultRouterProperties;

class RestResultDeliveryClientTests {

    @Test
    void legacyDeliveryEventOmitsOptionalLifecycleEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = objectMapper.writeValueAsString(new DeliveryEvent(
                "execution-1", "event-1", "CONVERSATION_START_COMPLETED", "connection-1",
                "user-1", null, Map.of("message", "hello")));

        assertThat(json)
                .contains(
                        "\"executionId\":\"execution-1\"",
                        "\"eventType\":\"CONVERSATION_START_COMPLETED\"",
                        "\"payload\":{\"message\":\"hello\"}")
                .doesNotContain("\"lifecycleEvent\"");
    }

    @Test
    void postsDeliveryEventToConfiguredOwnerEndpoint() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/deliveries", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            ResultRouterProperties properties = new ResultRouterProperties(
                    "default",
                    new ResultRouterProperties.Delivery(
                            Map.of("realtime-2", URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort())),
                            "/ai/deliveries",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2)));
            RestResultDeliveryClient client = new RestResultDeliveryClient(
                    properties,
                    new ConfiguredRealtimeInstanceEndpointResolver(properties),
                    new ObjectMapper()
                            .findAndRegisterModules()
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

            client.deliver(
                    new DeliveryTarget("connection-1", "realtime-2"),
                    new DeliveryEvent(
                            "execution-1", "event-1", "COMPLETED", "connection-1",
                            "user-1",
                            new LifecycleEvent(
                                    "returned_message_topic",
                                    "request-1",
                                    "client-event-3",
                                    3,
                                    "TOPICS_READY",
                                    Instant.parse("2026-11-24T06:08:49.955831Z")),
                            null,
                            Map.of("message", "hello")));

            assertThat(requestPath.get()).isEqualTo("/ai/deliveries");
            assertThat(requestBody.get()).contains(
                    "\"executionId\":\"execution-1\"",
                    "\"connectionId\":\"connection-1\"",
                    "\"lifecycleEvent\":{\"type\":\"returned_message_topic\"",
                    "\"lifecycleId\":\"request-1\"",
                    "\"eventId\":\"client-event-3\"",
                    "\"revision\":3",
                    "\"state\":\"TOPICS_READY\"",
                    "\"occurredAt\":\"2026-11-24T06:08:49.955831Z\"",
                    "\"message\":\"hello\"");
        } finally {
            server.stop(0);
        }
    }
}
