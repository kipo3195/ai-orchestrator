package com.ucware.ai_orchestrator.returnedmessage.application;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;

/** Client projection deliberately includes judgments without their internal reasons. */
public record ReturnedMessageClientResult(List<Topic> topics, Counts counts, Instant generatedAt) {

    public static ReturnedMessageClientResult from(ReturnedMessageTopicsResult result) {
        var messagesByRef = result.messageAssessments().stream().collect(Collectors.toMap(
                ReturnedMessageTopicsResult.MessageAssessment::messageRef, Function.identity()));
        List<Topic> topics = result.topics().stream().map(topic -> new Topic(
                        topic.topic(), topic.summary(),
                        topic.sourceMessageRefs().stream().map(ref -> {
                            var message = messagesByRef.get(ref);
                            if (message == null) {
                                throw new IllegalArgumentException("Missing message assessment");
                            }
                            return new Message(message.messageRef(), message.subject(), message.priority(),
                                    message.replyRequired(), message.actionRequired());
                        }).toList())).toList();
        List<Message> messages = topics.stream().flatMap(topic -> topic.messages().stream()).toList();
        return new ReturnedMessageClientResult(
                topics,
                new Counts(
                        messages.size(),
                        messages.stream().filter(message -> message.priority() == ReturnedMessageTopicsResult.Priority.URGENT).count(),
                        messages.stream().filter(Message::replyRequired).count(),
                        messages.stream().filter(Message::actionRequired).count(),
                        messages.stream().filter(message -> message.priority() == ReturnedMessageTopicsResult.Priority.UNKNOWN).count()),
                result.generatedAt());
    }

    public record Counts(long total, long urgent, long replyRequired, long actionRequired, long other) {}

    public record Topic(String topic, String summary, List<Message> messages) {}

    public record Message(String messageRef, String subject,
            ReturnedMessageTopicsResult.Priority priority,
            boolean replyRequired, boolean actionRequired) {}
}
