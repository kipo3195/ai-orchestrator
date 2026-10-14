package com.ucware.ai_orchestrator.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.AiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

class AiExecutionPolicyServiceTests {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsWhenSnapshotHasNotBeenInitialized() {
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                AiPolicySnapshot::uninitialized, userProvider);

        var decision = service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "user-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE);
        verifyNoInteractions(userProvider);
    }

    @Test
    void rejectsWhenGlobalPolicyIsDisabled() {
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = service(
                Map.of("tenant-1", false),
                Map.of(featureKey("tenant-1"), true),
                userProvider);

        var decision = service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "user-1");

        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
        verifyNoInteractions(userProvider);
    }

    @Test
    void missingGlobalPolicyDefaultsToDisabled() {
        AiExecutionPolicyService service = service(
                Map.of(), Map.of(featureKey("tenant-1"), true), mock(UserAiPolicyProvider.class));

        var decision = service.evaluateFeature("tenant-1", AiFeature.CONVERSATION_START);

        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
    }

    @Test
    void rejectsWhenFeaturePolicyIsDisabledOrMissing() {
        AiExecutionPolicyService disabledService = service(
                Map.of("tenant-1", true),
                Map.of(featureKey("tenant-1"), false),
                mock(UserAiPolicyProvider.class));
        AiExecutionPolicyService missingService = service(
                Map.of("tenant-1", true), Map.of(), mock(UserAiPolicyProvider.class));

        assertThat(disabledService.evaluateFeature("tenant-1", AiFeature.CONVERSATION_START).reason())
                .isEqualTo(AiExecutionPolicyReason.FEATURE_AI_DISABLED);
        assertThat(missingService.evaluateFeature("tenant-1", AiFeature.CONVERSATION_START).reason())
                .isEqualTo(AiExecutionPolicyReason.FEATURE_AI_DISABLED);
    }

    @Test
    void rejectsFeatureBeforeResolvingUserPolicy() {
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = service(
                Map.of("tenant-1", true),
                Map.of(featureKey("tenant-1"), false),
                userProvider);

        var decision = service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "user-1");

        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.FEATURE_AI_DISABLED);
        verifyNoInteractions(userProvider);
    }

    @Test
    void evaluatesGlobalAndFeatureWithoutResolvingUserPolicy() {
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = enabledService(userProvider);

        var decision = service.evaluateFeature("tenant-1", AiFeature.CONVERSATION_START);

        assertThat(decision.allowed()).isTrue();
        verifyNoInteractions(userProvider);
    }

    @Test
    void allowsEnabledOverallUserPolicyAfterGlobalAndFeature() {
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        when(userProvider.resolve("tenant-1", "user-1"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.ENABLED));
        AiExecutionPolicyService service = enabledService(userProvider);

        var decision = service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "user-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.ALLOWED);
    }

    @Test
    void mapsOverallUserPolicyRejections() {
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = enabledService(userProvider);

        when(userProvider.resolve("tenant-1", "disabled"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.DISABLED));
        when(userProvider.resolve("tenant-1", "missing"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.NOT_CONFIGURED));
        when(userProvider.resolve("tenant-1", "unavailable"))
                .thenReturn(UserAiPolicyResolution.unavailable());

        assertThat(service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "disabled").reason())
                .isEqualTo(AiExecutionPolicyReason.USER_AI_DISABLED);
        assertThat(service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "missing").reason())
                .isEqualTo(AiExecutionPolicyReason.USER_POLICY_NOT_CONFIGURED);
        assertThat(service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "unavailable").reason())
                .isEqualTo(AiExecutionPolicyReason.USER_POLICY_UNAVAILABLE);
    }

    @Test
    void rejectsInvalidInputsBeforeReadingProviders() {
        AiPolicyProvider policyProvider = mock(AiPolicyProvider.class);
        UserAiPolicyProvider userProvider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = new AiExecutionPolicyService(policyProvider, userProvider);

        assertThatThrownBy(() -> service.evaluateFeature("  ", AiFeature.CONVERSATION_START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId must not be blank");
        assertThatThrownBy(() -> service.evaluateFeature("tenant-1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("feature must not be null");
        assertThatThrownBy(() -> service.evaluateFeatureForUser(
                "tenant-1", AiFeature.CONVERSATION_START, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be blank");
        verifyNoInteractions(policyProvider, userProvider);
    }

    private static AiExecutionPolicyService enabledService(UserAiPolicyProvider userProvider) {
        return service(
                Map.of("tenant-1", true),
                Map.of(featureKey("tenant-1"), true),
                userProvider);
    }

    private static AiExecutionPolicyService service(
            Map<String, Boolean> globalPolicies,
            Map<FeaturePolicyKey, Boolean> featurePolicies,
            UserAiPolicyProvider userProvider) {
        AiPolicySnapshot snapshot = AiPolicySnapshot.initialized(
                globalPolicies, featurePolicies, LOADED_AT);
        return new AiExecutionPolicyService(() -> snapshot, userProvider);
    }

    private static FeaturePolicyKey featureKey(String tenantId) {
        return new FeaturePolicyKey(tenantId, AiFeature.CONVERSATION_START);
    }
}
