package com.ucware.ai_orchestrator.result.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ucware.ai_orchestrator.result.domain.RoutingRef;

class RedisRealtimeConnectionResolverTests {

    @Test
    void resolvesAndValidatesCurrentClientSessionOwner() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("rt:client-session-current:default:user-1:client-session-1"))
                .thenReturn("connection-1");
        when(values.get("rt:connection:default:connection-1"))
                .thenReturn("{\"userId\":\"user-1\",\"clientSessionId\":\"client-session-1\"," +
                        "\"ownerInstanceId\":\"realtime-2\",\"lastSeenAt\":\"ignored\"}");
        RedisRealtimeConnectionResolver resolver = new RedisRealtimeConnectionResolver(
                redisTemplate, new ObjectMapper());

        assertThat(resolver.resolve(RoutingRef.forClientSession(
                "default", "user-1", "client-session-1", null)))
                .hasValueSatisfying(target -> {
                    assertThat(target.connectionId()).isEqualTo("connection-1");
                    assertThat(target.ownerInstanceId()).isEqualTo("realtime-2");
                });
    }

    @Test
    void rejectsConnectionRecordFromDifferentSession() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("rt:client-session-current:default:user-1:client-session-1"))
                .thenReturn("connection-1");
        when(values.get("rt:connection:default:connection-1"))
                .thenReturn("{\"userId\":\"user-1\",\"clientSessionId\":\"other-session\"," +
                        "\"ownerInstanceId\":\"realtime-2\"}");
        RedisRealtimeConnectionResolver resolver = new RedisRealtimeConnectionResolver(
                redisTemplate, new ObjectMapper());

        assertThat(resolver.resolve(RoutingRef.forClientSession(
                "default", "user-1", "client-session-1", null))).isEmpty();
    }

    @Test
    void retriesWhenCurrentConnectionChangesDuringResolution() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        String currentKey = "rt:client-session-current:default:user-1:client-session-1";
        when(values.get(currentKey))
                .thenReturn("connection-1", "connection-2", "connection-2", "connection-2");
        when(values.get("rt:connection:default:connection-1"))
                .thenReturn("{\"userId\":\"user-1\",\"clientSessionId\":\"client-session-1\"," +
                        "\"ownerInstanceId\":\"realtime-1\"}");
        when(values.get("rt:connection:default:connection-2"))
                .thenReturn("{\"userId\":\"user-1\",\"clientSessionId\":\"client-session-1\"," +
                        "\"ownerInstanceId\":\"realtime-2\"}");
        RedisRealtimeConnectionResolver resolver = new RedisRealtimeConnectionResolver(
                redisTemplate, new ObjectMapper());

        assertThat(resolver.resolve(RoutingRef.forClientSession(
                "default", "user-1", "client-session-1", null)))
                .hasValueSatisfying(target -> {
                    assertThat(target.connectionId()).isEqualTo("connection-2");
                    assertThat(target.ownerInstanceId()).isEqualTo("realtime-2");
                });
    }
}
