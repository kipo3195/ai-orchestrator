package com.ucware.ai_orchestrator.result.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResultRoutingException(
                        "Realtime delivery returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultRoutingException("Realtime delivery was interrupted", e);
        } catch (IOException e) {
            throw new ResultRoutingException("Realtime delivery request failed", e);
        }
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
