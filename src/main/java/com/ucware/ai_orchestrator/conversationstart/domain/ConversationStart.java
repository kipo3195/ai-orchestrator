package com.ucware.ai_orchestrator.conversationstart.domain;

import java.time.Instant;
import java.util.Objects;

public class ConversationStart {

    private final String executionId;
    private final String userId;
    private final String roomId;
    private final String chatType;
    private final Instant enteredAt;
    private final Instant executeAt;
    private volatile ConversationStartStatus status;
    private volatile ConversationStartResult result;

    private ConversationStart(String executionId, String userId, String roomId, String chatType,
                              Instant enteredAt, Instant executeAt) {
        this.executionId = Objects.requireNonNull(executionId);
        this.userId = Objects.requireNonNull(userId);
        this.roomId = Objects.requireNonNull(roomId);
        this.chatType = Objects.requireNonNull(chatType);
        this.enteredAt = Objects.requireNonNull(enteredAt);
        this.executeAt = Objects.requireNonNull(executeAt);
        this.status = ConversationStartStatus.CREATED;
    }

    public static ConversationStart create(String executionId, String userId, String roomId,
                                           String chatType, Instant enteredAt, Instant executeAt) {
        return new ConversationStart(executionId, userId, roomId, chatType, enteredAt, executeAt);
    }

    public synchronized void schedule() {
        if (status != ConversationStartStatus.CREATED) {
            throw new IllegalStateException("Only created executions can be scheduled");
        }
        status = ConversationStartStatus.SCHEDULED;
    }

    public synchronized void startExecution() {
        if (status != ConversationStartStatus.SCHEDULED) {
            throw new IllegalStateException("Only scheduled executions can start");
        }
        status = ConversationStartStatus.EXECUTING;
    }

    public synchronized void complete(ConversationStartResult result) {
        if (status != ConversationStartStatus.EXECUTING) {
            throw new IllegalStateException("Only executing executions can complete");
        }
        if (result == null || !executionId.equals(result.sessionId())) {
            throw new IllegalArgumentException("AI result session does not match execution");
        }
        this.result = result;
        status = ConversationStartStatus.COMPLETED;
    }

    public synchronized boolean cancel() {
        if (!isActive()) {
            return false;
        }
        status = ConversationStartStatus.CANCELLED;
        return true;
    }

    public synchronized void fail() {
        if (status == ConversationStartStatus.SCHEDULED || status == ConversationStartStatus.EXECUTING) {
            status = ConversationStartStatus.FAILED;
        }
    }

    public boolean isExecutable() {
        return status == ConversationStartStatus.SCHEDULED;
    }

    public boolean isActive() {
        return status == ConversationStartStatus.CREATED
                || status == ConversationStartStatus.SCHEDULED
                || status == ConversationStartStatus.EXECUTING;
    }

    public String getExecutionId() { return executionId; }
    public String getUserId() { return userId; }
    public String getRoomId() { return roomId; }
    public String getChatType() { return chatType; }
    public Instant getEnteredAt() { return enteredAt; }
    public Instant getExecuteAt() { return executeAt; }
    public ConversationStartStatus getStatus() { return status; }
    public ConversationStartResult getResult() { return result; }
}
