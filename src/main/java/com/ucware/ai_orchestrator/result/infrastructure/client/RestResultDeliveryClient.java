package com.ucware.ai_orchestrator.result.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ucware.ai_orchestrator.result.application.ResultRoutingException;
import com.ucware.ai_orchestrator.result.application.port.RealtimeInstanceEndpointResolver;
import com.ucware.ai_orchestrator.result.application.port.ResultDeliveryPort;
import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.infrastructure.config.ResultRouterProperties;

@Component
public class RestResultDeliveryClient implements ResultDeliveryPort {

    private static final Logger logger = LoggerFactory.getLogger(RestResultDeliveryClient.class);

    private final ResultRouterProperties properties;
    private final ObjectMapper objectMapper;
    private final RealtimeInstanceEndpointResolver endpointResolver;
    private final HttpClient httpClient;

    public RestResultDeliveryClient(
            ResultRouterProperties properties,
            RealtimeInstanceEndpointResolver endpointResolver,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.endpointResolver = endpointResolver;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.delivery().connectTimeout())
                .build();
    }

    @Override
    public void deliver(DeliveryTarget target, DeliveryEvent event) {
        URI deliveryUri = deliveryUri(target.ownerInstanceId());
        HttpRequest request = HttpRequest.newBuilder(deliveryUri)
                .timeout(properties.delivery().requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeBody(event)))
                .build();

        logger.debug(
                "Delivering AI result. ownerInstanceId={}, executionId={}, eventId={}, eventType={}, uri={}",
                target.ownerInstanceId(), event.executionId(), event.eventId(), event.eventType(),
                deliveryUri);
        long startedAt = System.nanoTime();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            long elapsedMillis = elapsedMillis(startedAt);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn(
                        "AI result delivery returned a non-success response. ownerInstanceId={}, "
                                + "executionId={}, eventId={}, eventType={}, status={}, elapsedMs={}, uri={}",
                        target.ownerInstanceId(), event.executionId(), event.eventId(), event.eventType(),
                        response.statusCode(), elapsedMillis, deliveryUri);
                throw new ResultRoutingException(
                        "Realtime delivery returned HTTP " + response.statusCode());
            }
            logger.info(
                    "AI result delivered successfully. ownerInstanceId={}, executionId={}, eventId={}, "
                            + "eventType={}, status={}, elapsedMs={}",
                    target.ownerInstanceId(), event.executionId(), event.eventId(), event.eventType(),
                    response.statusCode(), elapsedMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn(
                    "AI result delivery was interrupted. ownerInstanceId={}, executionId={}, "
                            + "eventId={}, eventType={}, elapsedMs={}, uri={}",
                    target.ownerInstanceId(), event.executionId(), event.eventId(), event.eventType(),
                    elapsedMillis(startedAt), deliveryUri, e);
            throw new ResultRoutingException("Realtime delivery was interrupted", e);
        } catch (IOException e) {
            logger.warn(
                    "AI result delivery request failed. ownerInstanceId={}, executionId={}, "
                            + "eventId={}, eventType={}, elapsedMs={}, uri={}",
                    target.ownerInstanceId(), event.executionId(), event.eventId(), event.eventType(),
                    elapsedMillis(startedAt), deliveryUri, e);
            throw new ResultRoutingException("Realtime delivery request failed", e);
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private URI deliveryUri(String ownerInstanceId) {
        URI endpoint = endpointResolver.resolve(ownerInstanceId)
                .orElseThrow(() -> new ResultRoutingException(
                        "No delivery endpoint configured for realtime instance: "
                                + ownerInstanceId));

        String path = properties.delivery().path().startsWith("/")
                ? properties.delivery().path()
                : "/" + properties.delivery().path();

        String baseUrl = endpoint.toString().replaceAll("/+$", "");

        return URI.create(baseUrl + path);
    }

    private String writeBody(DeliveryEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new ResultRoutingException("Could not serialize realtime delivery event", e);
        }
    }
}
