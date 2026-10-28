package com.ucware.ai_orchestrator.returnedmessage.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.returnedmessage.application.port.AiExecutionGateway;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageSelection;
import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageTopicsResult;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageAiProperties;

@Component
@ConditionalOnProperty(
        prefix = "omni-ai.returned-message",
        name = "enabled",
        havingValue = "true")
public class OmniAiReturnedMessageExecutionClient implements AiExecutionGateway {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String MEDIA_TYPE_JSON = "application/json";
    private static final String TASK_TYPE = "RETURNED_MESSAGE_TOPICS";
    private static final int CONTRACT_VERSION = 1;

    private final ReturnedMessageAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OmniAiReturnedMessageExecutionClient(
            ReturnedMessageAiProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public ExecutionResult start(ExecutionCommand command) {
        StartExecutionRequest requestBody = new StartExecutionRequest(
                command.executionId(),
                command.tenantId(),
                TASK_TYPE,
                CONTRACT_VERSION,
                new ReturnedMessageInput(
                        command.requestId(),
                        command.userId(),
                        command.absencePeriodId(),
                        command.messageSelectionRef(),
                        command.selectedItems().stream()
                                .map(SelectedItemRequest::from)
                                .toList()));
        HttpRequest request = requestBuilder(properties.startUri(), command.tenantId())
                .POST(HttpRequest.BodyPublishers.ofString(writeBody(requestBody)))
                .build();

        HttpResponse<String> response = send(request, "start");
        if (response.statusCode() == 400 || response.statusCode() == 422) {
            return failed("AI_EXECUTION_REQUEST_INVALID");
        }
        if (response.statusCode() == 409) {
            return failed("AI_EXECUTION_CONFLICT");
        }
        requireSuccess(response, "start");
        return readResult(response.body(), command.executionId());
    }

    @Override
    public ExecutionResult reconcile(String tenantId, String executionId) {
        URI statusUri = properties.statusUri(encodePathSegment(executionId));
        HttpRequest request = requestBuilder(statusUri, tenantId).GET().build();

        HttpResponse<String> response = send(request, "status query");
        if (response.statusCode() == 404) {
            return new ExecutionResult(Status.NOT_FOUND, null, null);
        }
        requireSuccess(response, "status query");
        return readResult(response.body(), executionId);
    }

    private HttpRequest.Builder requestBuilder(URI uri, String tenantId) {
        return HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Content-Type", MEDIA_TYPE_JSON)
                .header("Accept", MEDIA_TYPE_JSON)
                .header(TENANT_HEADER, tenantId);
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Omni AI Returned Message " + operation + " was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Omni AI Returned Message " + operation + " failed", exception);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Omni AI Returned Message " + operation
                            + " returned HTTP " + response.statusCode());
        }
    }

    private String writeBody(StartExecutionRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize Omni AI Returned Message request", exception);
        }
    }

    private ExecutionResult readResult(String body, String expectedExecutionId) {
        try {
            ExecutionResponse response = objectMapper.readValue(body, ExecutionResponse.class);
            if (response == null
                    || !expectedExecutionId.equals(response.executionId())
                    || !TASK_TYPE.equals(response.taskType())
                    || response.contractVersion() != CONTRACT_VERSION
                    || response.status() == null) {
                throw new IllegalStateException(
                        "Omni AI Returned Message response does not match execution");
            }
            ReturnedMessageTopicsResult result = response.result() == null
                    ? null
                    : response.result().toDomain();
            String failureReason = response.error() == null
                    ? null
                    : response.error().code();
            return new ExecutionResult(
                    response.status(), result, failureReason);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not read Omni AI Returned Message response", exception);
        }
    }

    private static ExecutionResult failed(String reason) {
        return new ExecutionResult(Status.FAILED, null, reason);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record StartExecutionRequest(
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("task_type") String taskType,
            @JsonProperty("contract_version") int contractVersion,
            ReturnedMessageInput input) {
    }

    private record ReturnedMessageInput(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("absence_period_id") String absencePeriodId,
            @JsonProperty("message_selection_ref") String messageSelectionRef,
            @JsonProperty("selected_items") List<SelectedItemRequest> selectedItems) {
    }

    private record SelectedItemRequest(
            @JsonProperty("source_item_key") String sourceItemKey,
            String subject,
            int sequence) {

        private static SelectedItemRequest from(ReturnedMessageSelection.Item item) {
            return new SelectedItemRequest(
                    item.sourceItemKey(),
                    item.subject(),
                    item.sequence());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExecutionResponse(
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("task_type") String taskType,
            @JsonProperty("contract_version") int contractVersion,
            Status status,
            TopicsResultResponse result,
            ErrorResponse error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopicsResultResponse(
            List<TopicResponse> topics,
            @JsonProperty("generated_at") Instant generatedAt) {

        private ReturnedMessageTopicsResult toDomain() {
            return new ReturnedMessageTopicsResult(
                    topics.stream().map(TopicResponse::toDomain).toList(),
                    generatedAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopicResponse(
            String topic,
            String summary,
            ReturnedMessageTopicsResult.Priority priority,
            @JsonProperty("priority_reason") String priorityReason,
            @JsonProperty("message_count") int messageCount,
            @JsonProperty("source_message_refs") List<String> sourceMessageRefs) {

        private ReturnedMessageTopicsResult.Topic toDomain() {
            return new ReturnedMessageTopicsResult.Topic(
                    topic,
                    summary,
                    priority,
                    priorityReason,
                    messageCount,
                    sourceMessageRefs);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String code) {
    }
}
