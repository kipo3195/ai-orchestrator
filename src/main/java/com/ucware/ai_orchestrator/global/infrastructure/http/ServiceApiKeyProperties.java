package com.ucware.ai_orchestrator.global.infrastructure.http;

import java.net.http.HttpRequest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "service-api-keys")
public record ServiceApiKeyProperties(ApiKey ws, ApiKey ai) {

    public ServiceApiKeyProperties {
        ws = ws == null ? new ApiKey("X-Service-Key", "") : ws;
        ai = ai == null ? new ApiKey("X-API-Key", "") : ai;
    }

    public record ApiKey(String headerName, String value) {

        public ApiKey {
            value = value == null ? "" : value;
            if (headerName == null || !headerName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("API key header name must be a valid HTTP header name");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("API key must not contain line breaks");
            }
        }

        public HttpRequest.Builder applyTo(HttpRequest.Builder builder) {
            return value.isBlank() ? builder : builder.header(headerName, value);
        }

        @Override
        public String toString() {
            return "ApiKey[headerName=" + headerName + ", configured=" + !value.isBlank() + "]";
        }
    }
}
