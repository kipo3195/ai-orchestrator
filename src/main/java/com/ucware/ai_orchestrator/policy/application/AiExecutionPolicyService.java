package com.ucware.ai_orchestrator.policy.application;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyDecision;
import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

@Service
public class AiExecutionPolicyService {

    private final GlobalAiPolicyProvider globalAiPolicyProvider;
    private final UserAiPolicyProvider userAiPolicyProvider;

    public AiExecutionPolicyService(
            GlobalAiPolicyProvider globalAiPolicyProvider,
            UserAiPolicyProvider userAiPolicyProvider) {
        this.globalAiPolicyProvider = globalAiPolicyProvider;
        this.userAiPolicyProvider = userAiPolicyProvider;
    }

    public AiExecutionPolicyDecision evaluateGlobal(String tenantId) {
        Objects.requireNonNull(tenantId);
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        GlobalAiPolicySnapshot snapshot = globalAiPolicyProvider.currentSnapshot();

        if (!snapshot.initialized()) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE);
        }
        if (!snapshot.isEnabled(tenantId)) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
        }
        return AiExecutionPolicyDecision.allow();
    }

    public AiExecutionPolicyDecision evaluateUser(String tenantId, String userId) {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(userId, "userId");

        UserAiPolicyResolution resolution = userAiPolicyProvider.resolve(tenantId, userId);
        if (resolution instanceof UserAiPolicyResolution.Unavailable) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.USER_POLICY_UNAVAILABLE);
        }

        UserAiPolicyStatus status = ((UserAiPolicyResolution.Resolved) resolution).status();
        return switch (status) {
            case ENABLED -> AiExecutionPolicyDecision.allow();
            case DISABLED -> AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.USER_AI_DISABLED);
            case NOT_CONFIGURED -> AiExecutionPolicyDecision.rejected(
                    AiExecutionPolicyReason.USER_POLICY_NOT_CONFIGURED);
        };
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
