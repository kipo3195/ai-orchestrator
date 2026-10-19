package com.ucware.ai_orchestrator.returnedmessage.application;

import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyService;
import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyDecision;
import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessagePermissionChecker;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessagePermissionChecker.Purpose;

@Component
public class ReturnedMessageAccessEvaluator {

    private final AiExecutionPolicyService policyService;
    private final ReturnedMessagePermissionChecker permissionChecker;

    public ReturnedMessageAccessEvaluator(
            AiExecutionPolicyService policyService,
            ReturnedMessagePermissionChecker permissionChecker) {
        this.policyService = policyService;
        this.permissionChecker = permissionChecker;
    }

    public AccessDecision evaluateMessageSelection(
            String tenantId,
            String userId,
            String absencePeriodId) {
        return evaluate(tenantId, userId, absencePeriodId, Purpose.MESSAGE_SELECTION, null);
    }

    public AccessDecision evaluateAiContext(
            String tenantId,
            String userId,
            String absencePeriodId,
            String messageSelectionRef) {
        return evaluate(tenantId, userId, absencePeriodId, Purpose.AI_CONTEXT, messageSelectionRef);
    }

    private AccessDecision evaluate(
            String tenantId,
            String userId,
            String absencePeriodId,
            Purpose purpose,
            String messageSelectionRef) {
        AiExecutionPolicyDecision policyDecision;
        try {
            // evaluateFeatureForUser evaluates Global -> Feature -> User in that order.
            policyDecision = policyService.evaluateFeatureForUser(
                    tenantId, AiFeature.RETURNED_MESSAGE_TOPICS, userId);
        } catch (RuntimeException exception) {
            return AccessDecision.unavailable("POLICY_UNAVAILABLE");
        }
        if (!policyDecision.allowed()) {
            String reason = "POLICY_" + policyDecision.reason().name();
            return unavailable(policyDecision.reason())
                    ? AccessDecision.unavailable(reason)
                    : AccessDecision.denied(reason);
        }

        ReturnedMessagePermissionChecker.Decision permissionDecision;
        try {
            permissionDecision = permissionChecker.evaluate(
                    new ReturnedMessagePermissionChecker.PermissionRequest(
                            tenantId, userId, absencePeriodId, purpose, messageSelectionRef));
        } catch (RuntimeException exception) {
            return AccessDecision.unavailable("PERMISSION_UNAVAILABLE");
        }
        if (permissionDecision == null
                || permissionDecision == ReturnedMessagePermissionChecker.Decision.UNAVAILABLE) {
            return AccessDecision.unavailable("PERMISSION_UNAVAILABLE");
        }
        if (permissionDecision == ReturnedMessagePermissionChecker.Decision.DENIED) {
            return AccessDecision.denied("PERMISSION_DENIED");
        }
        return AccessDecision.allow();
    }

    private static boolean unavailable(AiExecutionPolicyReason reason) {
        return reason == AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE
                || reason == AiExecutionPolicyReason.USER_POLICY_UNAVAILABLE;
    }

    public record AccessDecision(Outcome outcome, String reason) {

        public enum Outcome {
            ALLOWED,
            DENIED,
            UNAVAILABLE
        }

        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }

        public boolean denied() {
            return outcome == Outcome.DENIED;
        }

        public boolean unavailable() {
            return outcome == Outcome.UNAVAILABLE;
        }

        static AccessDecision allow() {
            return new AccessDecision(Outcome.ALLOWED, null);
        }

        static AccessDecision denied(String reason) {
            return new AccessDecision(Outcome.DENIED, reason);
        }

        static AccessDecision unavailable(String reason) {
            return new AccessDecision(Outcome.UNAVAILABLE, reason);
        }
    }
}
