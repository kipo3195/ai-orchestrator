package com.ucware.ai_orchestrator.conversationstart.domain;

import java.util.Optional;

public interface ConversationStartRepository {

    // ConversationStartRepository는 단순 Redis 접근 추상화가 아니라 ConversationStart Aggregate의 영속성 계약에 가깝기 때문에 domain layer에 둔다.

    void save(ConversationStart conversationStart);

    Optional<ConversationStart> findById(String executionId);

    Optional<ConversationStart> findActiveByRoomSessionId(String roomSessionId);

    Optional<ConversationStart> findActive(
            String userId,
            String roomId
    );
    
}
