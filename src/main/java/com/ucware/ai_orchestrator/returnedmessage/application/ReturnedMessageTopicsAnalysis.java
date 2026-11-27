package com.ucware.ai_orchestrator.returnedmessage.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

/** Wire contract shared by the AI callback and execution polling response. */
public record ReturnedMessageTopicsAnalysis(
        List<Topic> topics,
        @JsonAlias("generated_at") Instant generatedAt) {

    public ReturnedMessageTopicsAnalysis {
        topics = List.copyOf(Objects.requireNonNull(topics));
        Objects.requireNonNull(generatedAt);
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("At least one topic is required");
        }
        var refs = new HashSet<String>();
        for (Topic topic : topics) {
            for (Message message : topic.messages()) {
                if (!refs.add(message.messageRef())) {
                    throw new IllegalArgumentException("Duplicate message reference");
                }
            }
        }
    }

    public ReturnedMessageTopicsResult toDomain() {
        return new ReturnedMessageTopicsResult(
                topics.stream().map(topic -> new ReturnedMessageTopicsResult.Topic(
                        topic.topic(), topic.summary(),
                        topic.messages().stream().map(Message::messageRef).toList())).toList(),
                topics.stream().flatMap(topic -> topic.messages().stream())
                        .map(Message::toDomain).toList(),
                generatedAt);
    }

    public record Topic(String topic, String summary, List<Message> messages) {
        public Topic {
            Objects.requireNonNull(topic);
            Objects.requireNonNull(summary);
            messages = List.copyOf(Objects.requireNonNull(messages));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("At least one message is required");
            }
        }
    }

    public record Message(
            @JsonAlias("message_ref") String messageRef,
            String subject,
            ReturnedMessageTopicsResult.Priority priority,
            @JsonAlias("priority_reason") String priorityReason,
            @JsonAlias("reply_required") boolean replyRequired,
            @JsonAlias("reply_reason") String replyReason,
            @JsonAlias("action_required") boolean actionRequired,
            @JsonAlias("action_reason") String actionReason) {

        public Message {
            if (messageRef == null || messageRef.isBlank()) {
                throw new IllegalArgumentException("Message reference is required");
            }
            Objects.requireNonNull(priority);
        }

        private ReturnedMessageTopicsResult.MessageAssessment toDomain() {
            return new ReturnedMessageTopicsResult.MessageAssessment(
                    messageRef, subject, priority, priorityReason,
                    replyRequired, replyReason, actionRequired, actionReason);
        }
    }
}
