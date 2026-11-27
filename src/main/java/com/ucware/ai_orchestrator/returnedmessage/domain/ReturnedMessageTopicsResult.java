package com.ucware.ai_orchestrator.returnedmessage.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReturnedMessageTopicsResult(
        List<Topic> topics,
        List<MessageAssessment> messageAssessments,
        Instant generatedAt) {

    public ReturnedMessageTopicsResult {
        topics = List.copyOf(Objects.requireNonNull(topics));
        messageAssessments = List.copyOf(Objects.requireNonNull(messageAssessments));
        Objects.requireNonNull(generatedAt);
    }

    public record Topic(
            String topic,
            String summary,
            List<String> sourceMessageRefs) {

        public Topic {
            Objects.requireNonNull(topic);
            Objects.requireNonNull(summary);
            sourceMessageRefs = List.copyOf(Objects.requireNonNull(sourceMessageRefs));
        }
    }

    public record MessageAssessment(
            String messageRef,
            String subject,
            Priority priority,
            String priorityReason,
            boolean replyRequired,
            String replyReason,
            boolean actionRequired,
            String actionReason) {

        public boolean hasRelevantJudgment() {
            return priority != Priority.UNKNOWN || replyRequired || actionRequired;
        }

        public MessageAssessment withRelevantReasons() {
            return new MessageAssessment(messageRef, subject, priority,
                    priority == Priority.UNKNOWN ? null : priorityReason,
                    replyRequired, replyRequired ? replyReason : null,
                    actionRequired, actionRequired ? actionReason : null);
        }

        public MessageAssessment {
            Objects.requireNonNull(messageRef);
            Objects.requireNonNull(priority);
        }
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        URGENT,
        UNKNOWN
    }
}
