package com.ucware.ai_orchestrator.policy.application.port;

import java.util.Map;

public interface PolicySnapshotRepository {

    Map<String, Boolean> findAllGlobalPolicies();
}
