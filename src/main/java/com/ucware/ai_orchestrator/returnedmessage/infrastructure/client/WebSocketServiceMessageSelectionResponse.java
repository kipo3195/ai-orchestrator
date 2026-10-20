package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebSocketServiceMessageSelectionResponse(
        List<Message> data,
        @JsonProperty("result_code") String resultCode,
        @JsonProperty("result_msg") String resultMessage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            @JsonProperty("msg_key") String messageKey) {
    }
}
