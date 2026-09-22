package com.ucware.ai_orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiOrchestratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiOrchestratorApplication.class, args);
	}

}
