package com.ucware.ai_orchestrator.result.infrastructure.redis;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ucware.ai_orchestrator.result.application.ResultRoutingException;
import com.ucware.ai_orchestrator.result.application.port.RealtimeConnectionResolver;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.result.domain.RoutingRef;
import com.ucware.ai_orchestrator.result.domain.RoutingTargetType;

@Component
public class RedisRealtimeConnectionResolver implements RealtimeConnectionResolver {

    private static final int MAX_RESOLUTION_ATTEMPTS = 2;
    private static final String CLIENT_SESSION_CURRENT_KEY =
            "rt:client-session-current:%s:%s:%s";
    private static final String CONNECTION_KEY = "rt:connection:%s:%s";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRealtimeConnectionResolver(StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DeliveryTarget> resolve(RoutingRef routingRef) {
        if (routingRef.targetType() != RoutingTargetType.CLIENT_SESSION_CURRENT) {
            throw new ResultRoutingException(
                    "Unsupported routing target type: " + routingRef.targetType());
        }

        String currentKey = CLIENT_SESSION_CURRENT_KEY.formatted(
                routingRef.tenantId(), routingRef.userId(), routingRef.clientSessionId());
        for (int attempt = 0; attempt < MAX_RESOLUTION_ATTEMPTS; attempt++) {
            String connectionId = redisTemplate.opsForValue().get(currentKey);
            if (!hasText(connectionId)) {
                return Optional.empty();
            }

            String connectionKey = CONNECTION_KEY.formatted(routingRef.tenantId(), connectionId);
            String connectionJson = redisTemplate.opsForValue().get(connectionKey);
            if (!hasText(connectionJson)) {
                continue;
            }

            RealtimeConnectionRecord connection = readConnection(connectionJson, connectionKey);
            String confirmedCurrentConnectionId = redisTemplate.opsForValue().get(currentKey);
            if (!connectionId.equals(confirmedCurrentConnectionId)) {
                continue;
            }
            if (!routingRef.userId().equals(connection.userId())
                    || !routingRef.clientSessionId().equals(connection.clientSessionId())) {
                return Optional.empty();
            }
            if (!hasText(connection.ownerInstanceId())) {
                return Optional.empty();
            }
            return Optional.of(new DeliveryTarget(connectionId, connection.ownerInstanceId()));
        }
        return Optional.empty();
    }

    private RealtimeConnectionRecord readConnection(String value, String key) {
        try {
            return objectMapper.readValue(value, RealtimeConnectionRecord.class);
        } catch (JsonProcessingException e) {
            throw new ResultRoutingException("Invalid realtime connection record at " + key, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RealtimeConnectionRecord(
            String userId,
            String clientSessionId,
            String ownerInstanceId) {
    }
}
