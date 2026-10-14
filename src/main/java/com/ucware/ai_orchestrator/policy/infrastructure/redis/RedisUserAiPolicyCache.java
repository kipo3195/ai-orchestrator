package com.ucware.ai_orchestrator.policy.infrastructure.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.ucware.ai_orchestrator.policy.application.port.UserAiPolicyCache;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;
import com.ucware.ai_orchestrator.policy.infrastructure.config.PolicyProperties;

@Repository
public class RedisUserAiPolicyCache implements UserAiPolicyCache {

    private static final String KEY_PREFIX = "ai:user:";
    private static final String KEY_SUFFIX = ":policy";

    private final StringRedisTemplate redisTemplate;
    private final Duration cacheTtl;
    private final Duration negativeCacheTtl;

    public RedisUserAiPolicyCache(
            StringRedisTemplate redisTemplate,
            PolicyProperties properties) {
        this.redisTemplate = redisTemplate;
        this.cacheTtl = properties.userCacheTtl();
        this.negativeCacheTtl = properties.userNegativeCacheTtl();
    }

    @Override
    public Optional<UserAiPolicyStatus> find(String tenantId, String userId) {
        // 값이 없는 Optional 생성
        String value = redisTemplate.opsForValue().get(key(tenantId, userId));
        if (value == null) {
            return Optional.empty();
        }

        try {
            // 반드시 값이 있어야 함. null이면 NPE, 
            return Optional.of(UserAiPolicyStatus.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown cached User AI policy value", exception);
        }
    }

    @Override
    public void put(String tenantId, String userId, UserAiPolicyStatus status) {
        Duration ttl = status == UserAiPolicyStatus.NOT_CONFIGURED
                ? negativeCacheTtl
                : cacheTtl;
        redisTemplate.opsForValue().set(key(tenantId, userId), status.name(), ttl);
    }

    private String key(String tenantId, String userId) {
        return KEY_PREFIX + tenantId + ":" + userId + KEY_SUFFIX;
    }
}
