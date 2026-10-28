package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omni-ai.returned-message")
public record ReturnedMessageAiProperties(
        URI baseUrl,
        String startPath,
        String statusPath,
        Duration connectTimeout,
        Duration requestTimeout) {

    private static final String DEFAULT_START_PATH =
            "/ai/v1/returned-message-topics/generate";
    private static final String DEFAULT_STATUS_PATH =
            "/ai/v1/returned-message-topics/executions/{executionId}";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    public ReturnedMessageAiProperties {
        startPath = hasText(startPath) ? startPath : DEFAULT_START_PATH;
        statusPath = hasText(statusPath) ? statusPath : DEFAULT_STATUS_PATH;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;

        if (!statusPath.contains("{executionId}")) {
            throw new IllegalArgumentException(
                    "omni-ai.returned-message.status-path must contain {executionId}");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
    }

    public URI startUri() {
        return resolve(startPath);
    }

    public URI statusUri(String encodedExecutionId) {
        return resolve(statusPath.replace("{executionId}", encodedExecutionId));
    }

    private URI resolve(String path) {
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "omni-ai.returned-message.base-url must be configured");
        }
        String normalizedBaseUrl = baseUrl.toString().replaceAll("/+$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBaseUrl + normalizedPath);
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "omni-ai.returned-message." + name + " must be positive");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
