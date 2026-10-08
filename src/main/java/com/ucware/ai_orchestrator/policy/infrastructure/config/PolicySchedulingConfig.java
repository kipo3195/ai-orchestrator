package com.ucware.ai_orchestrator.policy.infrastructure.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class PolicySchedulingConfig {

    @Bean
    public Clock policyClock() {
        return Clock.systemUTC();
    }
}
