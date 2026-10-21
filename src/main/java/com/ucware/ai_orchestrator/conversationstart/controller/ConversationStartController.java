package com.ucware.ai_orchestrator.conversationstart.controller;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ucware.ai_orchestrator.conversationstart.application.CancelConversationSuggestionUseCase;
import com.ucware.ai_orchestrator.conversationstart.application.StartConversationSuggestionUseCase;
//import com.ucware.ai_orchestrator.conversationstart.controller.ConversationStartController.EnterRoomRequest.ConversationStartResponse;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.execution.application.AiExecutionResponse;
import com.ucware.ai_orchestrator.routing.controller.RoutingRefRequest;
import com.ucware.ai_orchestrator.routing.domain.RoutingScope;

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
    public ResponseEntity<AiExecutionResponse<ConversationStartResponse>> enterRoom(
            @Valid @RequestBody EnterRoomRequest request,
            HttpServletRequest httpRequest) {
        ConversationStart execution = startUseCase.start(
                request.roomSessionId(),
                request.routingRef().toRoutingRef(
                        new RoutingScope("ROOM_SESSION", request.roomSessionId())),
                request.roomKey(),
                request.chatType());
        URI location = URI.create(httpRequest.getRequestURI() + "/executions/" + execution.getExecutionId());
        ConversationStartResponse featureResponse = new ConversationStartResponse(
                execution.getRoomSessionId(), execution.getRoomId(), execution.getChatType());
        AiExecutionResponse<ConversationStartResponse> body =
                AiExecutionResponse.from(execution.getExecution(), featureResponse);
        return ResponseEntity.created(location).body(body);
    }

    @DeleteMapping("/{roomSessionId}")
    public ResponseEntity<Void> leaveRoom(@PathVariable String roomSessionId,
                                          @RequestParam String userID,
                                          @RequestParam String roomKey) {
        if (!cancelUseCase.cancel(roomSessionId, userID, roomKey)) {
            throw new ResponseStatusException(NOT_FOUND, "Active conversation start not found");
        }
        return ResponseEntity.noContent().build();
    }

    public record EnterRoomRequest(
            @NotBlank String roomSessionId,
            @NotBlank String roomKey,
            String chatType,
            @NotNull @Valid RoutingRefRequest routingRef) {
    }

    public record ConversationStartResponse(String roomSessionId, String roomKey, String chatType) { }
}
