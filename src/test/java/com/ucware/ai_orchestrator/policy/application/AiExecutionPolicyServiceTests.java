package com.ucware.ai_orchestrator.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;

class AiExecutionPolicyServiceTests {

    @Test
    void rejectsWhenSnapshotHasNotBeenInitialized() {
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized);

        var decision = service.evaluateGlobal("tenant-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE);
    }

    @Test
    void missingTenantPolicyDefaultsToDisabled() {
        GlobalAiPolicySnapshot snapshot = GlobalAiPolicySnapshot.initialized(
                Map.of("tenant-1", true), Instant.parse("2026-01-01T00:00:00Z"));
        AiExecutionPolicyService service = new AiExecutionPolicyService(() -> snapshot);

        var decision = service.evaluateGlobal("tenant-2");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
    }

    @Test
    void allowsOnlyEnabledTenant() {
        GlobalAiPolicySnapshot snapshot = GlobalAiPolicySnapshot.initialized(
                Map.of("tenant-1", true, "tenant-2", false),
                Instant.parse("2026-01-01T00:00:00Z"));
        AiExecutionPolicyService service = new AiExecutionPolicyService(() -> snapshot);

        assertThat(service.evaluateGlobal("tenant-1").allowed()).isTrue();
        assertThat(service.evaluateGlobal("tenant-2").allowed()).isFalse();
    }
}
