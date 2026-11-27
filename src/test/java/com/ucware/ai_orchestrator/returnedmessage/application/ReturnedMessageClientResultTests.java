package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult.MessageAssessment;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult.Priority;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult.Topic;

class ReturnedMessageClientResultTests {

    @Test
    void countsMessagesAcrossTopicsWithOverlappingJudgments() {
        var result = new ReturnedMessageTopicsResult(
                List.of(
                        new Topic("검토", "요약", List.of("m1", "m2", "m3")),
                        new Topic("안내", "요약", List.of("m4", "m5"))),
                List.of(
                        message("m1", Priority.URGENT, true, true),
                        message("m2", Priority.URGENT, false, true),
                        message("m3", Priority.UNKNOWN, true, false),
                        message("m4", Priority.HIGH, false, false),
                        message("m5", Priority.NORMAL, false, false)),
                Instant.parse("2026-11-27T01:00:00Z"));

        var payload = ReturnedMessageClientResult.from(result);

        assertThat(payload.counts()).isEqualTo(
                new ReturnedMessageClientResult.Counts(5, 2, 2, 2, 1));
        assertThat(payload.topics()).hasSize(2);
        assertThat(payload.generatedAt()).isEqualTo(result.generatedAt());
    }

    @Test
    void countsUnknownMessageAsOther() {
        var result = new ReturnedMessageTopicsResult(
                List.of(new Topic("기타", "요약", List.of("m1"))),
                List.of(message("m1", Priority.UNKNOWN, false, false)),
                Instant.parse("2026-11-27T01:00:00Z"));

        assertThat(ReturnedMessageClientResult.from(result).counts()).isEqualTo(
                new ReturnedMessageClientResult.Counts(1, 0, 0, 0, 1));
    }

    @Test
    void returnsZeroCountsForEmptyResult() {
        var result = new ReturnedMessageTopicsResult(
                List.of(), List.of(), Instant.parse("2026-11-27T01:00:00Z"));

        assertThat(ReturnedMessageClientResult.from(result).counts()).isEqualTo(
                new ReturnedMessageClientResult.Counts(0, 0, 0, 0, 0));
    }

    private MessageAssessment message(String ref, Priority priority, boolean reply, boolean action) {
        return new MessageAssessment(ref, ref, priority, null, reply, null, action, null);
    }
}
