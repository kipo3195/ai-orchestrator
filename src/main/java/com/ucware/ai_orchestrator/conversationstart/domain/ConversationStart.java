package com.ucware.ai_orchestrator.conversationstart.domain;

import java.time.Instant;
import java.util.Objects;

import com.ucware.ai_orchestrator.execution.domain.AiExecution;
import com.ucware.ai_orchestrator.execution.domain.AiExecutionStatus;

public class ConversationStart {

    private final AiExecution execution;
    private final String roomSessionId;
    private final String userId;
    private final String roomId;
    private final String chatType;
    private volatile ConversationStartResult result;

    private ConversationStart(AiExecution execution, String roomSessionId,
                              String userId, String roomId, String chatType) {
        this.execution = Objects.requireNonNull(execution);
        this.roomSessionId = Objects.requireNonNull(roomSessionId);
        this.userId = Objects.requireNonNull(userId);
        this.roomId = Objects.requireNonNull(roomId);
        this.chatType = Objects.requireNonNull(chatType);
    }

    public static ConversationStart create(AiExecution execution, String roomSessionId,
                                           String userId, String roomId, String chatType) {
        return new ConversationStart(execution, roomSessionId, userId, roomId, chatType);
    }

    public synchronized void schedule() {
        execution.schedule();
    }

    public synchronized void startExecution() {
        execution.start();
    }

    public synchronized void complete(ConversationStartResult result) {
        if (result == null || !getExecutionId().equals(result.executionId())) {
            throw new IllegalArgumentException("AI result does not match execution");
        }
        execution.complete();
        this.result = result;
    }

    public synchronized boolean cancel() {
        return execution.cancel();
    }

    public synchronized void fail() {
        execution.fail();
    }

    public boolean isExecutable() {
        return execution.isScheduled();
    }

    public boolean isActive() {
        return execution.isActive();
    }

    public AiExecution getExecution() { return execution; }
    public String getExecutionId() { return execution.getExecutionId(); }
    public String getRoomSessionId() { return roomSessionId; }
    public String getUserId() { return userId; }
    public String getRoomId() { return roomId; }
    public String getChatType() { return chatType; }
    public Instant getEnteredAt() { return execution.getRequestedAt(); }
    public AiExecutionStatus getStatus() { return execution.getStatus(); }
    public ConversationStartResult getResult() { return result; }
}
