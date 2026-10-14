package com.ucware.ai_orchestrator.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ucware.ai_orchestrator.policy.domain.AiExecutionPolicyReason;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;

class AiExecutionPolicyServiceTests {

    @Test
    void rejectsWhenSnapshotHasNotBeenInitialized() {
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized,
                mock(UserAiPolicyProvider.class));

        var decision = service.evaluateGlobal("tenant-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_POLICY_UNAVAILABLE);
    }

    @Test
    void missingTenantPolicyDefaultsToDisabled() {
        GlobalAiPolicySnapshot snapshot = GlobalAiPolicySnapshot.initialized(
                Map.of("tenant-1", true), Instant.parse("2026-01-01T00:00:00Z"));
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                () -> snapshot,
                mock(UserAiPolicyProvider.class));

        var decision = service.evaluateGlobal("tenant-2");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.GLOBAL_AI_DISABLED);
    }

    @Test
    void rejectsBlankTenantId() {
        GlobalAiPolicyProvider provider = mock(GlobalAiPolicyProvider.class);
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                provider,
                mock(UserAiPolicyProvider.class));

        assertThatThrownBy(() -> service.evaluateGlobal("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId must not be blank");
        verifyNoInteractions(provider);
    }

    @Test
    void allowsOnlyEnabledTenant() {
        GlobalAiPolicySnapshot snapshot = GlobalAiPolicySnapshot.initialized(
                Map.of("tenant-1", true, "tenant-2", false),
                Instant.parse("2026-01-01T00:00:00Z"));
        // AiExecutionPolicyService 생성 시점에 snapshot을 전달하는 것이 아니라, snapshot을 반환하도록 구현된 GlobalAiPolicyProvider를 전달
        // () → snapshot 람다 표현식이 GlobalAiPolicyProvider의 구현 역할을 하므로 생성자 매개변수로 전달
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                () -> snapshot,
                mock(UserAiPolicyProvider.class));

        // GlobalAiPolicySnapshot current = globalAiPolicyProvider.currentSnapshot();
        // evaluateGlobal 함수 내부에서 위처럼 호출 하고 있으므로
        // 이렇게 호출하면 람다가 실행되어 앞서 생성한 snapshot 객체가 반환돼.
        // AiExecutionPolicyService 입장에서는 누가 GlobalAiPolicyProvider를 구현했는지 알 필요가 없다.
        // Port 기반 설계와 의존성 역전 원칙(DIP) 의 핵심
        assertThat(service.evaluateGlobal("tenant-1").allowed()).isTrue();
        assertThat(service.evaluateGlobal("tenant-2").allowed()).isFalse();
    }

    @Test
    void allowsEnabledUserPolicy() {
        UserAiPolicyProvider provider = mock(UserAiPolicyProvider.class);
        when(provider.resolve("tenant-1", "user-1"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.ENABLED));
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized,
                provider);

        var decision = service.evaluateUser("tenant-1", "user-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.ALLOWED);
    }

    @Test
    void rejectsDisabledUserPolicy() {
        UserAiPolicyProvider provider = mock(UserAiPolicyProvider.class);
        when(provider.resolve("tenant-1", "user-1"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.DISABLED));
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized,
                provider);

        var decision = service.evaluateUser("tenant-1", "user-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.USER_AI_DISABLED);
    }

    @Test
    void rejectsNotConfiguredUserPolicy() {
        UserAiPolicyProvider provider = mock(UserAiPolicyProvider.class);
        when(provider.resolve("tenant-1", "user-1"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.NOT_CONFIGURED));
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized,
                provider);

        var decision = service.evaluateUser("tenant-1", "user-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.USER_POLICY_NOT_CONFIGURED);
    }

    @Test
    void rejectsUnavailableUserPolicy() {
        UserAiPolicyProvider provider = mock(UserAiPolicyProvider.class);
        when(provider.resolve("tenant-1", "user-1"))
                .thenReturn(UserAiPolicyResolution.unavailable());
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized,
                provider);

        var decision = service.evaluateUser("tenant-1", "user-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AiExecutionPolicyReason.USER_POLICY_UNAVAILABLE);
    }

    @Test
    void rejectsBlankUserIdWithoutResolvingUserPolicy() {
        UserAiPolicyProvider provider = mock(UserAiPolicyProvider.class);
        AiExecutionPolicyService service = new AiExecutionPolicyService(
                GlobalAiPolicySnapshot::uninitialized,
                provider);
       
        assertThatThrownBy(() -> service.evaluateUser("tenant-1", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be blank");
        verifyNoInteractions(provider);
    }
}
