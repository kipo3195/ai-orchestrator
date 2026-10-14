package com.ucware.ai_orchestrator.policy.application;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyDecision;
import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.AiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

@Service
public class AiExecutionPolicyService {

    private final AiPolicyProvider policyProvider;
    private final UserAiPolicyProvider userAiPolicyProvider;

    public AiExecutionPolicyService(
            AiPolicyProvider policyProvider,
            UserAiPolicyProvider userAiPolicyProvider) {
        this.policyProvider = policyProvider;
        this.userAiPolicyProvider = userAiPolicyProvider;
    }

    public AiExecutionPolicyDecision evaluateFeature(String tenantId, AiFeature feature) {
        requireIdentifier(tenantId, "tenantId");
        Objects.requireNonNull(feature, "feature must not be null");

        AiPolicySnapshot snapshot = policyProvider.currentSnapshot();

        if (!snapshot.initialized()) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE);
        }
        if (!snapshot.isGlobalEnabled(tenantId)) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
        }
        if (!snapshot.isFeatureEnabled(tenantId, feature)) {
            return AiExecutionPolicyDecision.rejected(AiExecutionPolicyReason.FEATURE_AI_DISABLED);
        }
        return AiExecutionPolicyDecision.allow();
    }

    public AiExecutionPolicyDecision evaluateFeatureForUser(
            String tenantId,
            AiFeature feature,
            String userId) {
        requireIdentifier(userId, "userId");

        AiExecutionPolicyDecision featureDecision = evaluateFeature(tenantId, feature);
        if (!featureDecision.allowed()) {
            return featureDecision;
        }

        // 현재 User Policy는 사용자의 전체 AI 사용 여부만 평가한다.
        // 사용자별 기능 정책은 실제 관리 요구가 생길 때 별도 Provider로 확장한다.
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
