package com.ucware.ai_orchestrator.policy.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;
import com.ucware.ai_orchestrator.policy.infrastructure.config.PolicyProperties;

class RedisUserAiPolicyCacheTests {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration NEGATIVE_CACHE_TTL = Duration.ofSeconds(30);

    @Test
    void readsCachedStatus() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("ai:user:tenant-1:user-1:policy")).thenReturn("ENABLED");
        RedisUserAiPolicyCache cache = cache(redisTemplate);

        var result = cache.find("tenant-1", "user-1");

        assertThat(result).contains(UserAiPolicyStatus.ENABLED);
    }

    @Test
    void returnsEmptyOnlyWhenKeyDoesNotExist() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("ai:user:tenant-1:user-1:policy")).thenReturn(null);
        RedisUserAiPolicyCache cache = cache(redisTemplate);

        assertThat(cache.find("tenant-1", "user-1")).isEmpty();
    }

    @Test
    void rejectsUnknownCachedValueInsteadOfTreatingItAsMiss() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("ai:user:tenant-1:user-1:policy")).thenReturn("UNKNOWN");
        RedisUserAiPolicyCache cache = cache(redisTemplate);

        assertThatThrownBy(() -> cache.find("tenant-1", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown cached User AI policy value");
    }

    @Test
    void writesConfiguredPolicyWithRegularTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        RedisUserAiPolicyCache cache = cache(redisTemplate);

        cache.put("tenant-1", "user-1", UserAiPolicyStatus.DISABLED);

        verify(values).set(
                "ai:user:tenant-1:user-1:policy",
                "DISABLED",
                CACHE_TTL);
    }

    @Test
    void writesMissingPolicyWithNegativeCacheTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        RedisUserAiPolicyCache cache = cache(redisTemplate);

        cache.put("tenant-1", "user-1", UserAiPolicyStatus.NOT_CONFIGURED);

        verify(values).set(
                "ai:user:tenant-1:user-1:policy",
                "NOT_CONFIGURED",
                NEGATIVE_CACHE_TTL);
    }

    private static RedisUserAiPolicyCache cache(StringRedisTemplate redisTemplate) {
        PolicyProperties properties = new PolicyProperties(
                Duration.ofSeconds(5),
                CACHE_TTL,
                NEGATIVE_CACHE_TTL,
                1);
        return new RedisUserAiPolicyCache(redisTemplate, properties);
    }
}
