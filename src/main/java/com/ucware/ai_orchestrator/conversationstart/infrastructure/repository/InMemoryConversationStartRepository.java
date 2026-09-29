package com.ucware.ai_orchestrator.conversationstart.infrastructure.repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartRepository;

@Repository
public class InMemoryConversationStartRepository implements ConversationStartRepository {

    private final Map<String, ConversationStart> executionsById = new HashMap<>();
    private final Map<String, String> activeIdsByUserRoom = new HashMap<>();
    private final Map<String, String> activeIdsByRoomSession = new HashMap<>();

    @Override
    public synchronized void save(ConversationStart execution) {
        executionsById.put(execution.getExecutionId(), execution);
        activeIdsByUserRoom.put(key(execution.getUserId(), execution.getRoomId()), execution.getExecutionId());
        activeIdsByRoomSession.put(execution.getRoomSessionId(), execution.getExecutionId());
    }

    @Override
    public synchronized Optional<ConversationStart> findById(String executionId) {
        return Optional.ofNullable(executionsById.get(executionId));
    }

    @Override
    public synchronized Optional<ConversationStart> findActiveByRoomSessionId(String roomSessionId) {
        String executionId = activeIdsByRoomSession.get(roomSessionId);
        ConversationStart execution = executionsById.get(executionId);
        if (execution == null || !execution.isActive()) {
            activeIdsByRoomSession.remove(roomSessionId, executionId);
            return Optional.empty();
        }
        return Optional.of(execution);
    }

    @Override
    public synchronized Optional<ConversationStart> findActive(String userId, String roomId) {
        String key = key(userId, roomId);
        String executionId = activeIdsByUserRoom.get(key);
        ConversationStart execution = executionsById.get(executionId);
        if (execution == null || !execution.isActive()) {
            activeIdsByUserRoom.remove(key, executionId);
            return Optional.empty();
        }
        return Optional.of(execution);
    }

    private static String key(String userId, String roomId) {
        return userId + '\u0000' + roomId;
    }
}
