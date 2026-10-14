package com.ucware.ai_orchestrator.policy.domain;

public interface UserAiPolicyProvider {

    UserAiPolicyResolution resolve(String tenantId, String userId);
}
