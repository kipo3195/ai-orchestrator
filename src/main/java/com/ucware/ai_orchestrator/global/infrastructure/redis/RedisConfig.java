package com.ucware.ai_orchestrator.global.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import com.ucware.ai_orchestrator.conversationstart.infrastructure.persistence.redis.ConversationStartRedisModel;

@Configuration 
public class RedisConfig {

   @Bean
    public RedisTemplate<String, ConversationStartRedisModel> conversationStartRedisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, ConversationStartRedisModel> template =
                new RedisTemplate<>();

        template.setConnectionFactory(connectionFactory);

        return template;
    }
    
}
