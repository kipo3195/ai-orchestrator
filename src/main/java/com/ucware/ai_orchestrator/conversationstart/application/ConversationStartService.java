package com.ucware.ai_orchestrator.conversationstart.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationStartAiPort;
import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationStartScheduler;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartContext;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartDelayPolicy;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartRepository;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartResult;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationSuggestionKind;
import com.ucware.ai_orchestrator.conversationstart.infrastructure.config.ConversationStartProperties;
import com.ucware.ai_orchestrator.execution.domain.AiExecution;
import com.ucware.ai_orchestrator.execution.domain.AiExecutionStatus;
import com.ucware.ai_orchestrator.execution.domain.AiWorkflowType;

@Service
public class ConversationStartService
        implements StartConversationSuggestionUseCase, CancelConversationSuggestionUseCase {

    private static final Logger logger = LoggerFactory.getLogger(ConversationStartService.class);

    private final ConversationStartRepository repository;
    private final ConversationStartScheduler scheduler;
    private final ConversationStartDelayPolicy delayPolicy;
    private final ConversationStartProperties properties;
    private final ConversationStartAiPort aiPort;

    public ConversationStartService(ConversationStartRepository repository,
                                    ConversationStartScheduler scheduler,
                                    ConversationStartDelayPolicy delayPolicy,
                                    ConversationStartProperties properties,
                                    ConversationStartAiPort aiPort) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.delayPolicy = delayPolicy;
        this.properties = properties;
        this.aiPort = aiPort;
    }

    @Override
    public synchronized ConversationStart start(String roomSessionId, String userId, String roomId, String chatType) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Conversation start is disabled");
        }
        requireText(roomSessionId, "roomSessionId");
        requireText(userId, "userId");
        requireText(roomId, "roomId");

        ConversationStart existingSession = repository.findActiveByRoomSessionId(roomSessionId).orElse(null);
        if (existingSession != null) {
            if (!userId.equals(existingSession.getUserId()) || !roomId.equals(existingSession.getRoomId())) {
                throw new IllegalArgumentException("roomSessionId is already associated with another room presence");
            }
            return existingSession;
        }

        repository.findActive(userId, roomId).ifPresent(previous -> {
            previous.cancel();
            scheduler.cancel(previous.getExecutionId());
        });

        Instant enteredAt = Instant.now();
        AiExecution aiExecution = AiExecution.create(
                UUID.randomUUID().toString(), AiWorkflowType.CONVERSATION_START,
                enteredAt, enteredAt.plus(properties.triggerDelay()));
        ConversationStart execution = ConversationStart.create(
                aiExecution, roomSessionId, userId, roomId, normalizeChatType(chatType));
        execution.schedule();
        repository.save(execution);

        try {
            scheduler.schedule(execution.getExecutionId(),
                    delayPolicy.calculate(enteredAt, properties.triggerDelay()),
                    () -> executeSuggestion(execution.getExecutionId()));
        } catch (RuntimeException e) {
            execution.cancel();
            throw e;
        }
        return execution;
    }

    private void executeSuggestion(String executionId) {
        ConversationStart execution;
        synchronized (this) {
            execution = repository.findById(executionId).orElse(null);
            if (execution == null || !execution.isExecutable() || !isCurrentExecution(execution)) {
                return;
            }
            execution.startExecution();
        }

        try {
            // 최근 대화 기반 추천은 타입만 정의하고 현재는 TRENDING을 실행합니다.
            ConversationStartContext context = new ConversationStartContext(
                    executionId, execution.getRoomSessionId(), execution.getUserId(), execution.getRoomId(),
                    execution.getEnteredAt(), ConversationSuggestionKind.TRENDING, List.of());
            ConversationStartResult result = aiPort.generateSuggestion(context);

            synchronized (this) {
                if (execution.getStatus() != AiExecutionStatus.EXECUTING
                        || !isCurrentExecution(execution)) {
                    return;
                }
                execution.complete(result);
            }
            logger.info("Conversation start AI completed. executionId={}, suggestionCount={}",
                    executionId, result.suggestions().size());
        } catch (Exception e) {
            synchronized (this) {
                execution.fail();
            }
            logger.error("Conversation start AI failed. executionId={}", executionId, e);
        }
    }

    private boolean isCurrentExecution(ConversationStart execution) {
        boolean currentForUserRoom = repository.findActive(execution.getUserId(), execution.getRoomId())
                .map(current -> execution.getExecutionId().equals(current.getExecutionId()))
                .orElse(false);
        boolean currentForRoomSession = repository.findActiveByRoomSessionId(execution.getRoomSessionId())
                .map(current -> execution.getExecutionId().equals(current.getExecutionId()))
                .orElse(false);
        return currentForUserRoom && currentForRoomSession;
    }

    @Override
    public synchronized boolean cancel(String roomSessionId, String userId, String roomId) {
        requireText(roomSessionId, "roomSessionId");
        requireText(userId, "userId");
        requireText(roomId, "roomId");

        ConversationStart execution = repository.findActiveByRoomSessionId(roomSessionId).orElse(null);

        if (execution == null || !userId.equals(execution.getUserId())
                || !roomId.equals(execution.getRoomId())
                || !roomSessionId.equals(execution.getRoomSessionId())
                || !execution.cancel()) {
            return false;
        }
        scheduler.cancel(execution.getExecutionId());
        return true;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String normalizeChatType(String chatType) {
        return "open".equalsIgnoreCase(chatType) ? "open" : "chat";
    }
}
