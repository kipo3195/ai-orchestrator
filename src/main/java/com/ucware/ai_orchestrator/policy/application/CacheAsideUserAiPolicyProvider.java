package com.ucware.ai_orchestrator.policy.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.policy.application.port.UserAiPolicyCache;
import com.ucware.ai_orchestrator.policy.application.port.UserAiPolicyRepository;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicy;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

@Service
public class CacheAsideUserAiPolicyProvider implements UserAiPolicyProvider {

    private static final Logger logger = LoggerFactory.getLogger(CacheAsideUserAiPolicyProvider.class);

    private final UserAiPolicyCache cache;
    private final UserAiPolicyRepository repository;
    private final Semaphore fallbackPermits;

    public CacheAsideUserAiPolicyProvider(
            UserAiPolicyCache cache,
            UserAiPolicyRepository repository,
            Semaphore userPolicyFallbackSemaphore) {
        this.cache = Objects.requireNonNull(cache);
        this.repository = Objects.requireNonNull(repository);
        this.fallbackPermits = Objects.requireNonNull(userPolicyFallbackSemaphore);
    }

    @Override
    public UserAiPolicyResolution resolve(String tenantId, String userId) {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(userId, "userId");

        try {
            Optional<UserAiPolicyStatus> cachedStatus = cache.find(tenantId, userId);
            // Cache hit이면 저장된 정책 상태를 반환한다.
            if (cachedStatus.isPresent()) {
                return UserAiPolicyResolution.resolved(cachedStatus.get());
            }
        } catch (RuntimeException exception) {
            logger.warn("User AI policy cache read failed; using DB fallback", exception);
        }

        // Redis cache miss 또는 read failure 이후 DB fallback 진입을 시도
        // 사용 가능한 permit이 있으면 하나를 원자적으로 획득하며,
        // tryAcquire는 모든 permit이 사용 중이면 대기하지 않고 즉시 실패한다.
        // acquire()는 permit이 생길 때까지 기다린다.
        if (!fallbackPermits.tryAcquire()) {
            logger.warn("User AI policy DB fallback rejected by concurrency limit");
            return UserAiPolicyResolution.unavailable();
        }
        UserAiPolicyStatus status;

        try {
            Optional<UserAiPolicy> policy = repository.findByTenantIdAndUserId(tenantId, userId);
            status = policy
                    .map(UserAiPolicyStatus::from)
                    .orElse(UserAiPolicyStatus.NOT_CONFIGURED);
        } catch (RuntimeException exception) {
            logger.warn("User AI policy DB fallback failed", exception);
            return UserAiPolicyResolution.unavailable();
        } finally {
            // 성공 또는 실패와 관계없이 획득한 permit을 반환한다.
            fallbackPermits.release();
        }
        
        // DB fallback permit은 DB 조회 완료 직후 반환한다. (finally)
        // Redis Write 지연이 DB fallback 동시 실행 한도를 점유하지 않도록
        // cache write는 permit 반환 후 best effort로 수행한다.
        // 단, Redis Write 자체의 동시 실행 수는 이 semaphore로 제한하지 않는다.
        cacheBestEffort(tenantId, userId, status);
        return UserAiPolicyResolution.resolved(status);
    }

    private void cacheBestEffort(String tenantId, String userId, UserAiPolicyStatus status) {
        try {
            cache.put(tenantId, userId, status);
        } catch (RuntimeException exception) {
            logger.warn("User AI policy cache write failed; using the DB result", exception);
        }
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
