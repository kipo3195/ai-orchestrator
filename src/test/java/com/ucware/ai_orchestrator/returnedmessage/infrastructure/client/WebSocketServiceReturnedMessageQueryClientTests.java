package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery.MessageSelection;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery.MessageSelectionQuery;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository.CreateSelection;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageQueryProperties;

class WebSocketServiceReturnedMessageQueryClientTests {

    private static final Instant OFFLINE_SINCE = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant LOOKBACK_SINCE = Instant.parse("2026-10-20T00:30:00Z");
    private static final Instant RETURNED_AT = Instant.parse("2026-10-20T01:00:00Z");
    private static final Instant SELECTED_AT = Instant.parse("2026-10-20T01:00:01Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-20T01:05:01Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsMsgUnreadContractAndStoresMessageKeysAsSelection() throws Exception {
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("""
                {
                  "data": [
                    {
                      "msg_read_date": "000000000000000000",
                      "msg_key": "MSG_KEY_1",
                      "msg_send_date": "20261020153045123",
                      "msg_subject": "업무 보고",
                      "msg_content": "본문"
                    },
                    {
                      "msg_read_date": "000000000000000000",
                      "msg_key": "MSG_KEY_2",
                      "msg_subject": "업무 요청"
                    }
                  ],
                  "result_code": "100",
                  "result_msg": "success"
                }
                """, 200, requestMethod, requestPath, requestBody);

        ReturnedMessageSelectionRepository selectionRepository =
                mock(ReturnedMessageSelectionRepository.class);
        when(selectionRepository.create(any(), any())).thenReturn(selection());

        try {
            MessageSelection result = client(server, selectionRepository)
                    .selectUnreadMessages(query());

            assertThat(result).isEqualTo(new MessageSelection(
                    "period-1", 2, "selection-1", SELECTED_AT, EXPIRES_AT));
            assertThat(requestMethod.get()).isEqualTo("POST");
            assertThat(requestPath.get()).isEqualTo("/rest/his_note");
            assertThat(objectMapper.readTree(requestBody.get())).isEqualTo(objectMapper.readTree("""
                    {
                      "sqlName": "MSG_UNREAD",
                      "startDate": "2026-10-20T00:00:00Z",
                      "endDate": "2026-10-20T01:00:00Z",
                      "searchDate": "99999999999999999",
                      "urgent": "N",
                      "keyword": "",
                      "searchOpt": [""],
                      "labelCode": "",
                      "reqcount": 30,
                      "reqpage": 1
                    }
                    """));

            ArgumentCaptor<CreateSelection> selectionCaptor =
                    ArgumentCaptor.forClass(CreateSelection.class);
            verify(selectionRepository).create(
                    selectionCaptor.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
            assertThat(selectionCaptor.getValue().items())
                    .extracting(ReturnedMessageSelection.Item::sourceItemKey)
                    .containsExactly("MSG_KEY_1", "MSG_KEY_2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptySuccessfulResultDoesNotCreateSelection() throws IOException {
        HttpServer server = server("""
                {"data": [], "result_code": "100", "result_msg": "success"}
                """, 200, null, null, null);
        ReturnedMessageSelectionRepository selectionRepository =
                mock(ReturnedMessageSelectionRepository.class);

        try {
            MessageSelection result = client(server, selectionRepository)
                    .selectUnreadMessages(query());

            assertThat(result).isEqualTo(new MessageSelection(
                    "period-1", 0, null, null, null));
            verify(selectionRepository, never()).create(any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsApplicationLevelErrorResult() throws IOException {
        HttpServer server = server("""
                {"result_code": "400", "result_msg": "param error"}
                """, 200, null, null, null);

        try {
            assertThatThrownBy(() -> client(
                    server, mock(ReturnedMessageSelectionRepository.class))
                    .selectUnreadMessages(query()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("WebSocket Service message query failed with result code 400");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonSuccessHttpResponse() throws IOException {
        HttpServer server = server("", 503, null, null, null);

        try {
            assertThatThrownBy(() -> client(
                    server, mock(ReturnedMessageSelectionRepository.class))
                    .selectUnreadMessages(query()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("WebSocket Service message selection returned HTTP 503");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(
            String responseBody,
            int status,
            AtomicReference<String> requestMethod,
            AtomicReference<String> requestPath,
            AtomicReference<String> requestBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/his_note", exchange -> {
            if (requestMethod != null) {
                requestMethod.set(exchange.getRequestMethod());
            }
            if (requestPath != null) {
                requestPath.set(exchange.getRequestURI().getPath());
            }
            if (requestBody != null) {
                requestBody.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private WebSocketServiceReturnedMessageQueryClient client(
            HttpServer server,
            ReturnedMessageSelectionRepository selectionRepository) {
        ReturnedMessageQueryProperties properties = new ReturnedMessageQueryProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "/rest/his_note",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofMinutes(5));
        return new WebSocketServiceReturnedMessageQueryClient(
                properties, selectionRepository, objectMapper);
    }

    private MessageSelectionQuery query() {
        return new MessageSelectionQuery(
                "request-1",
                "tenant-1",
                "user-1",
                "period-1",
                OFFLINE_SINCE,
                LOOKBACK_SINCE,
                RETURNED_AT);
    }

    private ReturnedMessageSelection selection() {
        return new ReturnedMessageSelection(
                "selection-1",
                "request-1",
                "tenant-1",
                "user-1",
                "period-1",
                SELECTED_AT,
                EXPIRES_AT,
                List.of(
                        new ReturnedMessageSelection.Item(
                                "WEBSOCKET_SERVICE", "MESSAGE", "MSG_KEY_1", 0),
                        new ReturnedMessageSelection.Item(
                                "WEBSOCKET_SERVICE", "MESSAGE", "MSG_KEY_2", 1)));
    }
}
