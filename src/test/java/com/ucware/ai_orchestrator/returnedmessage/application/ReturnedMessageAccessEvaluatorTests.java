package com.ucware.ai_orchestrator.returnedmessage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyService;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessagePermissionChecker;
import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessagePermissionChecker.Purpose;

class ReturnedMessageAccessEvaluatorTests {

    @Test
    void globalAiDisabledDeniesBeforePermissionEvaluation() {
        ReturnedMessagePermissionChecker permissionChecker = mock(ReturnedMessagePermissionChecker.class);
        ReturnedMessageAccessEvaluator evaluator = evaluator(
                snapshot(false, true), permissionChecker);

        ReturnedMessageAccessEvaluator.AccessDecision decision =
                evaluator.evaluateMessageSelection("tenant-1", "user-1", "period-1");

        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).isEqualTo("POLICY_GLOBAL_AI_DISABLED");
        verifyNoInteractions(permissionChecker);
    }

    @Test
    void unavailableGlobalPolicyIsRetryableInsteadOfDenied() {
        ReturnedMessagePermissionChecker permissionChecker = mock(ReturnedMessagePermissionChecker.class);
        AiExecutionPolicyService policyService = new AiExecutionPolicyService(
                AiPolicySnapshot::uninitialized,
                (tenantId, userId) -> new UserAiPolicyResolution.Resolved(UserAiPolicyStatus.ENABLED));
        ReturnedMessageAccessEvaluator evaluator = new ReturnedMessageAccessEvaluator(
                policyService, permissionChecker);

        ReturnedMessageAccessEvaluator.AccessDecision decision =
                evaluator.evaluateMessageSelection("tenant-1", "user-1", "period-1");

        assertThat(decision.unavailable()).isTrue();
        assertThat(decision.reason()).isEqualTo("POLICY_GLOBAL_POLICY_UNAVAILABLE");
        verifyNoInteractions(permissionChecker);
    }

    @Test
    void permissionPurposeDistinguishesSelectionFromAiContext() {
        ReturnedMessagePermissionChecker permissionChecker = mock(ReturnedMessagePermissionChecker.class);
        when(permissionChecker.evaluate(any()))
                .thenReturn(ReturnedMessagePermissionChecker.Decision.ALLOWED);
        ReturnedMessageAccessEvaluator evaluator = evaluator(
                snapshot(true, true), permissionChecker);

        assertThat(evaluator.evaluateMessageSelection(
                "tenant-1", "user-1", "period-1").allowed()).isTrue();
        assertThat(evaluator.evaluateAiContext(
                "tenant-1", "user-1", "period-1", "selection-1").allowed()).isTrue();

        verify(permissionChecker).evaluate(new ReturnedMessagePermissionChecker.PermissionRequest(
                "tenant-1", "user-1", "period-1", Purpose.MESSAGE_SELECTION, null));
        verify(permissionChecker).evaluate(new ReturnedMessagePermissionChecker.PermissionRequest(
                "tenant-1", "user-1", "period-1", Purpose.AI_CONTEXT, "selection-1"));
    }

    private ReturnedMessageAccessEvaluator evaluator(
            AiPolicySnapshot snapshot,
            ReturnedMessagePermissionChecker permissionChecker) {
        AiExecutionPolicyService policyService = new AiExecutionPolicyService(
                () -> snapshot,
                (tenantId, userId) -> new UserAiPolicyResolution.Resolved(UserAiPolicyStatus.ENABLED));
        return new ReturnedMessageAccessEvaluator(policyService, permissionChecker);
    }

    private AiPolicySnapshot snapshot(boolean globalEnabled, boolean featureEnabled) {
        return AiPolicySnapshot.initialized(
                Map.of("tenant-1", globalEnabled),
                Map.of(new FeaturePolicyKey(
                        "tenant-1", AiFeature.RETURNED_MESSAGE_TOPICS), featureEnabled),
                Instant.parse("2026-10-19T06:00:00Z"));
    }
}
