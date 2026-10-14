package com.ucware.ai_orchestrator.policy.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;
import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotData;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryAiPolicyStore;

@Component
public class PolicySnapshotRefresher {

    private static final Logger logger = LoggerFactory.getLogger(PolicySnapshotRefresher.class);

    private final PolicySnapshotRepository repository;
    private final InMemoryAiPolicyStore store;
    private final Clock clock;

    public PolicySnapshotRefresher(PolicySnapshotRepository repository,
                                   InMemoryAiPolicyStore store,
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
            logger.warn("AI policy snapshot refresh failed; keeping the last valid snapshot", exception);
        }
    }

    void refresh() {
        PolicySnapshotData data = repository.findAllPolicies();
        Instant loadedAt = clock.instant();
        store.replace(AiPolicySnapshot.initialized(
                data.globalPolicies(), data.featurePolicies(), loadedAt));
        logger.debug("AI policy snapshot refreshed. globalPolicyCount={}, featurePolicyCount={}, loadedAt={}",
                data.globalPolicies().size(), data.featurePolicies().size(), loadedAt);
    }
}
