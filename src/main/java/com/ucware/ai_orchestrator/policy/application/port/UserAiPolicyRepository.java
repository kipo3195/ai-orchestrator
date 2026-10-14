package com.ucware.ai_orchestrator.policy.application.port;

import java.util.Optional;

import com.ucware.ai_orchestrator.policy.domain.UserAiPolicy;

public interface UserAiPolicyRepository {

    Optional<UserAiPolicy> findByTenantIdAndUserId(String tenantId, String userId);
}
