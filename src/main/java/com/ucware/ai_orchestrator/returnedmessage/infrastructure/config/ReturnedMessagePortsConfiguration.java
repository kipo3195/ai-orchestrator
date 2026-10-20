package com.ucware.ai_orchestrator.returnedmessage.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageConfirmationSender;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessagePermissionChecker;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageQuery;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageResultSender;

@Configuration
public class ReturnedMessagePortsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ReturnedMessagePermissionChecker allowingReturnedMessagePermissionChecker() {
        return request -> ReturnedMessagePermissionChecker.Decision.ALLOWED;
    }

    @Bean
    @ConditionalOnMissingBean
    ReturnedMessageQuery unavailableReturnedMessageQuery() {
        return query -> {
            throw new IllegalStateException("Returned Message Query adapter is not configured");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    ReturnedMessageConfirmationSender unavailableReturnedMessageConfirmationSender() {
        return prompt -> {
            throw new IllegalStateException("Returned Message confirmation adapter is not configured");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    AiExecutionGateway unavailableReturnedMessageAiExecutionGateway() {
        return new AiExecutionGateway() {
            @Override
            public ExecutionResult start(ExecutionCommand command) {
                return new ExecutionResult(Status.UNKNOWN, null, null);
            }

            @Override
            public ExecutionResult reconcile(String tenantId, String executionId) {
                return new ExecutionResult(Status.UNKNOWN, null, null);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    ReturnedMessageResultSender unavailableReturnedMessageResultSender() {
        return command -> ReturnedMessageResultSender.DeliveryOutcome.RETRYABLE_FAILURE;
    }
}
