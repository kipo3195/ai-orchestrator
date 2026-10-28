package com.ucware.ai_orchestrator.returnedmessage.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReturnedMessageTopicsResult(
        List<Topic> topics,
        Instant generatedAt) {

    public ReturnedMessageTopicsResult {
        topics = List.copyOf(Objects.requireNonNull(topics));
        Objects.requireNonNull(generatedAt);
    }

    public record Topic(
            String topic,
            String summary,
            Priority priority,
            String priorityReason,
            int messageCount,
            List<String> sourceMessageRefs) {

        public Topic {
            Objects.requireNonNull(topic);
            Objects.requireNonNull(summary);
            Objects.requireNonNull(priority);
            sourceMessageRefs = List.copyOf(Objects.requireNonNull(sourceMessageRefs));
            if (messageCount < 1) {
                throw new IllegalArgumentException("messageCount must be positive");
            }
        }
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }
}
