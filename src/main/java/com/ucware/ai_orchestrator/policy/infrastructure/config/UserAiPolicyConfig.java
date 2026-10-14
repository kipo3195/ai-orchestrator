package com.ucware.ai_orchestrator.policy.infrastructure.config;

import java.util.concurrent.Semaphore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserAiPolicyConfig {

    @Bean
    public Semaphore userPolicyFallbackSemaphore(PolicyProperties properties) {
        return new Semaphore(properties.userFallbackMaxConcurrency());
    }
}
