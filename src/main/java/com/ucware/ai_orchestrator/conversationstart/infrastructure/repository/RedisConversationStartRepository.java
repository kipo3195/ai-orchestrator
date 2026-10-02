package com.ucware.ai_orchestrator.conversationstart.infrastructure.repository;

import java.util.Optional;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartRepository;
import com.ucware.ai_orchestrator.conversationstart.infrastructure.persistence.redis.ConversationStartRedisModel;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;


@Repository
public class RedisConversationStartRepository implements ConversationStartRepository{

    private final RedisTemplate<String, ConversationStartRedisModel> redisTemplate;

    public RedisConversationStartRepository(
            RedisTemplate<String, ConversationStartRedisModel> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(ConversationStart conversationStart) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'save'");
    }

    @Override
    public Optional<ConversationStart> findById(String executionId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findById'");
    }

    @Override
    public Optional<ConversationStart> findActiveByRoomSessionId(String roomSessionId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findActiveByRoomSessionId'");
    }

    @Override
    public Optional<ConversationStart> findActive(String userId, String roomId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findActive'");
    }
    
}
