package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.client.OmniAiReturnedMessageExecutionClient;

class ReturnedMessageAiClientActivationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "omni-ai.returned-message.base-url=http://127.0.0.1:8000",
                    "omni-ai.returned-message.connect-timeout=3s",
                    "omni-ai.returned-message.request-timeout=5s");

    @Test
    void usesFallbackGatewayWhenClientIsDisabled() {
        contextRunner
                .withPropertyValues("omni-ai.returned-message.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            OmniAiReturnedMessageExecutionClient.class);
                    assertThat(context).hasSingleBean(AiExecutionGateway.class);
                });
    }

    @Test
    void usesHttpGatewayWhenClientIsEnabled() {
        contextRunner
                .withPropertyValues("omni-ai.returned-message.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            OmniAiReturnedMessageExecutionClient.class);
                    assertThat(context).hasSingleBean(AiExecutionGateway.class);
                    assertThat(context.getBean(AiExecutionGateway.class))
                            .isInstanceOf(OmniAiReturnedMessageExecutionClient.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReturnedMessageAiProperties.class)
    @Import({
            OmniAiReturnedMessageExecutionClient.class,
            ReturnedMessagePortsConfiguration.class
    })
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
