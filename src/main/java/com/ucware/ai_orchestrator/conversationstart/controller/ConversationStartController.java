package com.ucware.ai_orchestrator.conversationstart.controller;

import java.net.URI;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ucware.ai_orchestrator.conversationstart.application.CancelConversationSuggestionUseCase;
import com.ucware.ai_orchestrator.conversationstart.application.StartConversationSuggestionUseCase;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartStatus;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/${api.version}/conversation-starts")
public class ConversationStartController {

    private final StartConversationSuggestionUseCase startUseCase;
    private final CancelConversationSuggestionUseCase cancelUseCase;

    public ConversationStartController(StartConversationSuggestionUseCase startUseCase,
                                       CancelConversationSuggestionUseCase cancelUseCase) {
        this.startUseCase = startUseCase;
        this.cancelUseCase = cancelUseCase;
    }

    @PostMapping
    public ResponseEntity<ConversationStartResponse> enterRoom(@Valid @RequestBody EnterRoomRequest request,
                                                               HttpServletRequest httpRequest) {
        ConversationStart execution = startUseCase.start(request.userID(), request.roomKey(), request.chatType());
        URI location = URI.create(httpRequest.getRequestURI() + "/" + execution.getExecutionId());
        ConversationStartResponse body = new ConversationStartResponse(
                execution.getExecutionId(), execution.getRoomId(), execution.getChatType(),
                execution.getStatus(), execution.getEnteredAt(), execution.getExecuteAt());
        return ResponseEntity.created(location).body(body);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> leaveRoom(@PathVariable String sessionId,
                                          @RequestParam String userID,
                                          @RequestParam String roomKey) {
        if (!cancelUseCase.cancel(userID, roomKey, sessionId)) {
            throw new ResponseStatusException(NOT_FOUND, "Active conversation start not found");
        }
        return ResponseEntity.noContent().build();
    }

    public record EnterRoomRequest(@JsonProperty("userID") @NotBlank String userID,
                                   @NotBlank String roomKey, String chatType) { }

    public record ConversationStartResponse(String sessionId, String roomKey, String chatType,
                                            ConversationStartStatus status, Instant enteredAt,
                                            Instant suggestionTriggerAt) { }
}
