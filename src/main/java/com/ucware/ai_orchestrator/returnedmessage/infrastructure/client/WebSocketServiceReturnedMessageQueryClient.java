package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageSelectionRepository.CreateSelection;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageQueryProperties;

@Component
@ConditionalOnProperty(prefix = "returned-message.message-query", name = "base-url")
public class WebSocketServiceReturnedMessageQueryClient implements ReturnedMessageQuery {

    private static final Logger logger = LoggerFactory.getLogger(
            WebSocketServiceReturnedMessageQueryClient.class);
    private static final String SUCCESS_RESULT_CODE = "100";
    private static final String SOURCE_SYSTEM = "WEBSOCKET_SERVICE";
    private static final String SOURCE_ITEM_TYPE = "MESSAGE";

    private final URI selectionUri;
    private final ReturnedMessageQueryProperties properties;
    private final ReturnedMessageSelectionRepository selectionRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebSocketServiceReturnedMessageQueryClient(
            ReturnedMessageQueryProperties properties,
            ReturnedMessageSelectionRepository selectionRepository,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.selectionRepository = selectionRepository;
        this.objectMapper = objectMapper;
        this.selectionUri = properties.selectionUri();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public MessageSelection selectUnreadMessages(MessageSelectionQuery query) {
        WebSocketServiceMessageSelectionRequest requestBody =
                new WebSocketServiceMessageSelectionRequest(
                        "MSG_UNREAD",
                        String.valueOf(query.offlineSince()),
                        String.valueOf(query.returnedAt()),
                        "99999999999999999",
                        "N",
                        "",
                        List.of(""),
                        "",
                        30,
                        1);

        HttpRequest request = HttpRequest.newBuilder(selectionUri)
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Service-Key", "1234")
                .header("X-Acting-User-Id", query.userId())
                .POST(HttpRequest.BodyPublishers.ofString(writeBody(requestBody)))
                .build();
        logger.info("WebSocket Service request URI: {}", request.uri());

        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn(
                        "WebSocket Service message selection returned a non-success response. "
                                + "requestId={}, absencePeriodId={}, status={}, elapsedMs={}",
                        query.requestId(), query.absencePeriodId(), response.statusCode(),
                        elapsedMillis(startedAt));
                throw new IllegalStateException(
                        "WebSocket Service message selection returned HTTP "
                                + response.statusCode());
            }

            WebSocketServiceMessageSelectionResponse responseBody = objectMapper.readValue(
                    response.body(), WebSocketServiceMessageSelectionResponse.class);
            return toMessageSelection(query, responseBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "WebSocket Service message selection request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "WebSocket Service message selection request failed", exception);
        }
    }

    private String writeBody(WebSocketServiceMessageSelectionRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize WebSocket Service message selection request", exception);
        }
    }

    private MessageSelection toMessageSelection(
            MessageSelectionQuery query,
            WebSocketServiceMessageSelectionResponse response) {
        if (response == null) {
            throw new IllegalStateException(
                    "WebSocket Service returned an empty message selection response");
        }
        if (!SUCCESS_RESULT_CODE.equals(response.resultCode())) {
            logger.warn(
                    "WebSocket Service message query failed. requestId={}, resultCode={}, resultMessage={}",
                    query.requestId(), response.resultCode(), response.resultMessage());
            throw new IllegalStateException(
                    "WebSocket Service message query failed with result code "
                            + response.resultCode());
        }
        if (response.data() == null) {
            throw new IllegalStateException(
                    "WebSocket Service success response does not contain data");
        }
        if (response.data().isEmpty()) {
            return new MessageSelection(query.absencePeriodId(), 0, null, null, null);
        }

        List<ReturnedMessageSelection.Item> items = selectionItems(response.data());
        ReturnedMessageSelection selection = selectionRepository.create(
                new CreateSelection(
                        query.requestId(),
                        query.tenantId(),
                        query.userId(),
                        query.absencePeriodId(),
                        items),
                properties.selectionTtl());
        return new MessageSelection(
                selection.absencePeriodId(),
                selection.items().size(),
                selection.messageSelectionRef(),
                selection.selectedAt(),
                selection.expiresAt());
    }

    private List<ReturnedMessageSelection.Item> selectionItems(
            List<WebSocketServiceMessageSelectionResponse.Message> messages) {
        List<ReturnedMessageSelection.Item> items = new ArrayList<>(messages.size());
        Set<String> messageKeys = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            WebSocketServiceMessageSelectionResponse.Message message = messages.get(index);
            String messageKey = message == null ? null : message.messageKey();
            if (messageKey == null || messageKey.isBlank() || !messageKeys.add(messageKey)) {
                throw new IllegalStateException(
                        "WebSocket Service returned an invalid message selection");
            }
            items.add(new ReturnedMessageSelection.Item(
                    SOURCE_SYSTEM,
                    SOURCE_ITEM_TYPE,
                    messageKey,
                    index));
        }
        return List.copyOf(items);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
