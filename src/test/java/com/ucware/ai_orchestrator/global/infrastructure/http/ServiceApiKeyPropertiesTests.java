package com.ucware.ai_orchestrator.global.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ServiceApiKeyPropertiesTests {

    @Test
    void bindsSeparateKeysAndHeadersWithoutExposingValuesInToString() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues(
                        "service-api-keys.ws.header-name=X-Service-Key",
                        "service-api-keys.ws.value=ws-test-secret",
                        "service-api-keys.ai.header-name=X-Custom-AI-Key",
                        "service-api-keys.ai.value=ai-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServiceApiKeyProperties keys = context.getBean(ServiceApiKeyProperties.class);
                    assertThat(keys.ws().applyTo(request()).build().headers()
                            .firstValue("X-Service-Key")).contains("ws-test-secret");
                    assertThat(keys.ai().applyTo(request()).build().headers()
                            .firstValue("X-Custom-AI-Key")).contains("ai-test-secret");
                    assertThat(keys.toString()).doesNotContain("ws-test-secret", "ai-test-secret");
                });
    }

    @Test
    void unsetKeysDoNotSendAuthenticationHeaders() {
        ServiceApiKeyProperties keys = new ServiceApiKeyProperties(null, null);
        assertThat(keys.ws().applyTo(request()).build().headers().map()).isEmpty();
        assertThat(keys.ai().applyTo(request()).build().headers().map()).isEmpty();
    }

    @Test
    void rejectsHeaderInjectionWithoutEchoingTheKey() {
        assertThatThrownBy(() -> new ServiceApiKeyProperties.ApiKey("X-Key", "secret\r\nHost: invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("API key must not contain line breaks");
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1/"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceApiKeyProperties.class)
    static class Config {
    }
}
