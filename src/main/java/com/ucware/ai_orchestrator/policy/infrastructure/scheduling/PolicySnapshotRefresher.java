package com.ucware.ai_orchestrator.policy.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryGlobalAiPolicyStore;

@Component
public class PolicySnapshotRefresher {

    private static final Logger logger = LoggerFactory.getLogger(PolicySnapshotRefresher.class);

    private final PolicySnapshotRepository repository;
    private final InMemoryGlobalAiPolicyStore store;
    private final Clock clock;

    public PolicySnapshotRefresher(PolicySnapshotRepository repository,
                                   InMemoryGlobalAiPolicyStore store,
                                   Clock clock) {
        this.repository = repository;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${policy.snapshot-refresh-interval}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException exception) {
            logger.warn("Global AI policy snapshot refresh failed; keeping the last valid snapshot", exception);
        }
    }

    void refresh() {
        Map<String, Boolean> tenantPolicies = repository.findAllGlobalPolicies();
        Instant loadedAt = clock.instant();
        store.replace(GlobalAiPolicySnapshot.initialized(tenantPolicies, loadedAt));
        logger.debug("Global AI policy snapshot refreshed. tenantCount={}, loadedAt={}",
                tenantPolicies.size(), loadedAt);
    }
}
