package com.ucware.ai_orchestrator.result.infrastructure.redis;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.result.application.ResultRoutingException;
import com.ucware.ai_orchestrator.result.application.port.RealtimeConnectionResolver;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;
import com.ucware.ai_orchestrator.routing.domain.RoutingRef;
import com.ucware.ai_orchestrator.routing.domain.RoutingTargetType;

@Component
public class RedisRealtimeConnectionResolver implements RealtimeConnectionResolver {

    private static final Logger logger = LoggerFactory.getLogger(
            RedisRealtimeConnectionResolver.class);
    private static final int MAX_RESOLUTION_ATTEMPTS = 2;
    private static final String CLIENT_SESSION_CURRENT_KEY =
            "rt:client-session-current:%s:%s:%s";
    private static final String CONNECTION_KEY = "rt:connection:%s:%s";

    private final StringRedisTemplate redisTemplate;

    public RedisRealtimeConnectionResolver(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<DeliveryTarget> resolve(RoutingRef routingRef) {
        if (routingRef.targetType() != RoutingTargetType.CLIENT_SESSION_CURRENT) {
            logger.warn(
                    "Realtime connection resolution rejected unsupported target. "
                            + "tenantId={}, userId={}, targetType={}",
                    routingRef.tenantId(), routingRef.userId(), routingRef.targetType());
            throw new ResultRoutingException(
                    "Unsupported routing target type: " + routingRef.targetType());
        }

        String currentKey = CLIENT_SESSION_CURRENT_KEY.formatted(
                routingRef.tenantId(), routingRef.userId(), routingRef.clientSessionId());
        String lastConnectionId = null;
        String retryFailureReason = "RESOLUTION_ATTEMPTS_EXHAUSTED";
        for (int attempt = 0; attempt < MAX_RESOLUTION_ATTEMPTS; attempt++) {
            String connectionId = redisTemplate.opsForValue().get(currentKey);
            lastConnectionId = connectionId;
            if (!hasText(connectionId)) {
                return unresolved(
                        routingRef, "CURRENT_CONNECTION_POINTER_MISSING", null, null);
            }

            String connectionKey = CONNECTION_KEY.formatted(routingRef.tenantId(), connectionId);
            Map<Object, Object> connectionValues = redisTemplate.opsForHash().entries(connectionKey);
            if (connectionValues.isEmpty()) {
                retryFailureReason = "CONNECTION_RECORD_MISSING";
                logger.debug(
                        "Realtime connection resolution will retry. reason={}, tenantId={}, "
                                + "userId={}, clientSessionId={}, connectionId={}, attempt={}",
                        retryFailureReason, routingRef.tenantId(), routingRef.userId(),
                        routingRef.clientSessionId(), connectionId, attempt + 1);
                continue;
            }

            RealtimeConnectionRecord connection = new RealtimeConnectionRecord(
                    stringValue(connectionValues.get("userId")),
                    stringValue(connectionValues.get("clientSessionId")),
                    stringValue(connectionValues.get("ownerInstanceId")),
                    integerValue(connectionValues.get("connectionType")));
            String confirmedCurrentConnectionId = redisTemplate.opsForValue().get(currentKey);
            if (!connectionId.equals(confirmedCurrentConnectionId)) {
                retryFailureReason = "CURRENT_CONNECTION_POINTER_CHANGED";
                logger.debug(
                        "Realtime connection resolution will retry. reason={}, tenantId={}, "
                                + "userId={}, clientSessionId={}, connectionId={}, "
                                + "confirmedConnectionId={}, attempt={}",
                        retryFailureReason, routingRef.tenantId(), routingRef.userId(),
                        routingRef.clientSessionId(), connectionId,
                        confirmedCurrentConnectionId, attempt + 1);
                continue;
            }
            if (!routingRef.userId().equals(connection.userId())
                    || !routingRef.clientSessionId().equals(connection.clientSessionId())) {
                return unresolved(
                        routingRef, "CONNECTION_OWNER_MISMATCH", connectionId, connection);
            }
            if (routingRef.connectionType() != null
                    && !routingRef.connectionType().equals(connection.connectionType())) {
                return unresolved(
                        routingRef, "CONNECTION_TYPE_MISMATCH", connectionId, connection);
            }
            if (!hasText(connection.ownerInstanceId())) {
                return unresolved(
                        routingRef, "OWNER_INSTANCE_MISSING", connectionId, connection);
            }
            logger.debug(
                    "Realtime connection resolved. tenantId={}, userId={}, clientSessionId={}, "
                            + "connectionType={}, connectionId={}, ownerInstanceId={}",
                    routingRef.tenantId(), routingRef.userId(), routingRef.clientSessionId(),
                    routingRef.connectionType(), connectionId, connection.ownerInstanceId());
            return Optional.of(new DeliveryTarget(connectionId, connection.ownerInstanceId()));
        }
        return unresolved(routingRef, retryFailureReason, lastConnectionId, null);
    }

    private Optional<DeliveryTarget> unresolved(
            RoutingRef routingRef,
            String reason,
            String connectionId,
            RealtimeConnectionRecord connection) {
        logger.warn(
                "Realtime connection resolution failed. reason={}, tenantId={}, "
                        + "expectedUserId={}, expectedClientSessionId={}, "
                        + "expectedConnectionType={}, connectionId={}, actualUserId={}, "
                        + "actualClientSessionId={}, actualConnectionType={}, ownerInstanceId={}",
                reason,
                routingRef.tenantId(),
                routingRef.userId(),
                routingRef.clientSessionId(),
                routingRef.connectionType(),
                connectionId,
                connection == null ? null : connection.userId(),
                connection == null ? null : connection.clientSessionId(),
                connection == null ? null : connection.connectionType(),
                connection == null ? null : connection.ownerInstanceId());
        return Optional.empty();
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record RealtimeConnectionRecord(
            String userId,
            String clientSessionId,
            String ownerInstanceId,
            Integer connectionType) {
    }
}
