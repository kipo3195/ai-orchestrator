package com.ucware.ai_orchestrator.policy.application.port;

import java.util.Optional;

import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

public interface UserAiPolicyCache {

    Optional<UserAiPolicyStatus> find(String tenantId, String userId);

    void put(String tenantId, String userId, UserAiPolicyStatus status);
}
