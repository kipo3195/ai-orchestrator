package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "returned-message.message-query")
public record ReturnedMessageQueryProperties(
        URI baseUrl,
        String path,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration selectionTtl) {

    private static final String DEFAULT_PATH = "/rest/his_note";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_SELECTION_TTL = Duration.ofMinutes(5);

    public ReturnedMessageQueryProperties {
        path = path == null || path.isBlank() ? DEFAULT_PATH : path;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        selectionTtl = selectionTtl == null ? DEFAULT_SELECTION_TTL : selectionTtl;

        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        requirePositive(selectionTtl, "selection-ttl");
    }

    public URI selectionUri() {
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "returned-message.message-query.base-url must be configured");
        }
        String normalizedBaseUrl = baseUrl.toString().replaceAll("/+$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBaseUrl + normalizedPath);
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "returned-message.message-query." + name + " must be positive");
        }
    }
}
