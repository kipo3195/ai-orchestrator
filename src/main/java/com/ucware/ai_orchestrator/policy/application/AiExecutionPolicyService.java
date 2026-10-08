package com.ucware.ai_orchestrator.policy.application;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyDecision;
import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;

@Service
public class AiExecutionPolicyService {

    private final GlobalAiPolicyProvider globalAiPolicyProvider;

    public AiExecutionPolicyService(GlobalAiPolicyProvider globalAiPolicyProvider) {
        this.globalAiPolicyProvider = globalAiPolicyProvider;
    }

    public AiExecutionPolicyDecision evaluateGlobal(String tenantId) {
        Objects.requireNonNull(tenantId);
        GlobalAiPolicySnapshot snapshot = globalAiPolicyProvider.currentSnapshot();

        if (!snapshot.initialized()) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE);
        }
        if (!snapshot.isEnabled(tenantId)) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
        }
        return AiExecutionPolicyDecision.allow();
    }
}
