package com.ucware.ai_orchestrator.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.application.port.UserAiPolicyCache;
import com.ucware.ai_orchestrator.policy.application.port.UserAiPolicyRepository;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicy;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

class CacheAsideUserAiPolicyProviderTests {

    @Test
    void returnsCachedStatusWithoutCallingRepository() {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1"))
                .thenReturn(Optional.of(UserAiPolicyStatus.DISABLED));
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);

        UserAiPolicyResolution result = provider.resolve("tenant-1", "user-1");

        assertResolved(result, UserAiPolicyStatus.DISABLED);
        verifyNoInteractions(repository);
        verify(cache, never()).put("tenant-1", "user-1", UserAiPolicyStatus.DISABLED);
    }

    @Test
    void cachesDatabasePolicyAfterCacheMiss() {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenReturn(Optional.of(new UserAiPolicy("tenant-1", "user-1", true)));
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);

        UserAiPolicyResolution result = provider.resolve("tenant-1", "user-1");

        assertResolved(result, UserAiPolicyStatus.ENABLED);
        verify(cache).put("tenant-1", "user-1", UserAiPolicyStatus.ENABLED);
    }

    @Test
    void negativelyCachesMissingDatabasePolicy() {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenReturn(Optional.empty());
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);

        UserAiPolicyResolution result = provider.resolve("tenant-1", "user-1");

        assertResolved(result, UserAiPolicyStatus.NOT_CONFIGURED);
        verify(cache).put("tenant-1", "user-1", UserAiPolicyStatus.NOT_CONFIGURED);
    }

    @Test
    void usesDatabaseFallbackWhenCacheReadFails() {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenReturn(Optional.of(new UserAiPolicy("tenant-1", "user-1", false)));
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);

        UserAiPolicyResolution result = provider.resolve("tenant-1", "user-1");

        assertResolved(result, UserAiPolicyStatus.DISABLED);
    }

    @Test
    void keepsDatabaseResultWhenCacheWriteFails() {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenReturn(Optional.of(new UserAiPolicy("tenant-1", "user-1", true)));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(cache).put("tenant-1", "user-1", UserAiPolicyStatus.ENABLED);
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);

        UserAiPolicyResolution result = provider.resolve("tenant-1", "user-1");

        assertResolved(result, UserAiPolicyStatus.ENABLED);
    }

    @Test
    void returnsUnavailableWithoutCachingWhenDatabaseFails() {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);

        UserAiPolicyResolution result = provider.resolve("tenant-1", "user-1");

        assertThat(result).isInstanceOf(UserAiPolicyResolution.Unavailable.class);
        verify(cache, never()).put(
                "tenant-1", "user-1", UserAiPolicyStatus.NOT_CONFIGURED);
    }

    @Test
    void rejectsConcurrentFallbackBeforeCallingRepository() throws Exception {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1")).thenReturn(Optional.empty());
        when(cache.find("tenant-1", "user-2")).thenReturn(Optional.empty());

        CountDownLatch repositoryEntered = new CountDownLatch(1);
        CountDownLatch releaseRepository = new CountDownLatch(1);
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenAnswer(invocation -> {
                    repositoryEntered.countDown();
                    if (!releaseRepository.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                    return Optional.of(new UserAiPolicy("tenant-1", "user-1", true));
                });
        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<UserAiPolicyResolution> first = executor.submit(
                    () -> provider.resolve("tenant-1", "user-1"));
            assertThat(repositoryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            UserAiPolicyResolution second = provider.resolve("tenant-1", "user-2");

            assertThat(second).isInstanceOf(UserAiPolicyResolution.Unavailable.class);
            verify(repository, never()).findByTenantIdAndUserId("tenant-1", "user-2");

            releaseRepository.countDown();
            assertResolved(first.get(5, TimeUnit.SECONDS), UserAiPolicyStatus.ENABLED);
        } finally {
            releaseRepository.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesFallbackPermitBeforeCacheWriteCompletes() throws Exception {
        UserAiPolicyCache cache = mock(UserAiPolicyCache.class);
        UserAiPolicyRepository repository = mock(UserAiPolicyRepository.class);
        when(cache.find("tenant-1", "user-1")).thenReturn(Optional.empty());
        when(cache.find("tenant-1", "user-2")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndUserId("tenant-1", "user-1"))
                .thenReturn(Optional.of(new UserAiPolicy("tenant-1", "user-1", true)));
        when(repository.findByTenantIdAndUserId("tenant-1", "user-2"))
                .thenReturn(Optional.of(new UserAiPolicy("tenant-1", "user-2", false)));

        CountDownLatch cacheWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseCacheWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            cacheWriteEntered.countDown();
            if (!releaseCacheWrite.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
            }
            return null;
        }).when(cache).put("tenant-1", "user-1", UserAiPolicyStatus.ENABLED);

        CacheAsideUserAiPolicyProvider provider = provider(cache, repository, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<UserAiPolicyResolution> first = executor.submit(
                    () -> provider.resolve("tenant-1", "user-1"));
            assertThat(cacheWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();

            UserAiPolicyResolution second = provider.resolve("tenant-1", "user-2");

            assertResolved(second, UserAiPolicyStatus.DISABLED);
            verify(repository).findByTenantIdAndUserId("tenant-1", "user-2");

            releaseCacheWrite.countDown();
            assertResolved(first.get(5, TimeUnit.SECONDS), UserAiPolicyStatus.ENABLED);
        } finally {
            releaseCacheWrite.countDown();
            executor.shutdownNow();
        }
    }

    private static CacheAsideUserAiPolicyProvider provider(
            UserAiPolicyCache cache,
            UserAiPolicyRepository repository,
            int maxConcurrency) {
        return new CacheAsideUserAiPolicyProvider(
                cache,
                repository,
                new Semaphore(maxConcurrency));
    }

    private static void assertResolved(
            UserAiPolicyResolution result,
            UserAiPolicyStatus expectedStatus) {
        assertThat(result).isInstanceOf(UserAiPolicyResolution.Resolved.class);
        assertThat(((UserAiPolicyResolution.Resolved) result).status()).isEqualTo(expectedStatus);
    }
}
