package com.ucware.ai_orchestrator.conversationstart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationStartAiPort;
import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationStartScheduler;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStart;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartContext;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartDelayPolicy;
import com.ucware.ai_orchestrator.conversationstart.domain.ConversationStartResult;
import com.ucware.ai_orchestrator.conversationstart.infrastructure.config.ConversationStartProperties;
import com.ucware.ai_orchestrator.conversationstart.infrastructure.repository.InMemoryConversationStartRepository;
import com.ucware.ai_orchestrator.execution.domain.AiExecutionStatus;
import com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyService;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyResolution;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicyStatus;
import com.ucware.ai_orchestrator.result.application.AiResultRouter;
import com.ucware.ai_orchestrator.result.application.RoutingRefFactory;
import com.ucware.ai_orchestrator.result.domain.ResultEvent;
import com.ucware.ai_orchestrator.result.infrastructure.config.ResultRouterProperties;

class ConversationStartServiceTests {

    @Test
    void startStoresExecutionByRoomSessionId() {
        TestContext context = new TestContext();

        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1","user-1", "room-1", "chat");

        assertThat(execution.getExecutionId()).isNotBlank();
        assertThat(execution.getRoomSessionId()).isEqualTo("room-session-1");
        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.SCHEDULED);
        assertThat(context.repository.findActiveByRoomSessionId("room-session-1"))
                .containsSame(execution);
    }

    @Test
    void reenterCancelsPreviousExecutionAndCreatesNewCorrelation() {
        TestContext context = new TestContext();
        ConversationStart previous = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");

        ConversationStart current = context.service.start(
                "room-session-2", "client-session-1", "user-1", "room-1", "chat");

        assertThat(previous.getStatus()).isEqualTo(AiExecutionStatus.CANCELLED);
        assertThat(current.getExecutionId()).isNotEqualTo(previous.getExecutionId());
        assertThat(context.repository.findActiveByRoomSessionId("room-session-1")).isEmpty();
        assertThat(context.repository.findActiveByRoomSessionId("room-session-2"))
                .containsSame(current);
        verify(context.scheduler).cancel(previous.getExecutionId());
    }

    @Test
    void duplicateEnterForSameRoomSessionReturnsExistingExecution() {
        TestContext context = new TestContext();
        ConversationStart first = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");

        ConversationStart duplicate = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");

        assertThat(duplicate).isSameAs(first);
        verify(context.scheduler, times(1)).schedule(any(), any(), any());
    }

    @Test
    void leaveCancelsOnlyExecutionMappedToRoomSession() {
        TestContext context = new TestContext();
        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");

        assertThat(context.service.cancel("unknown-session", "user-1", "room-1")).isFalse();
        assertThat(context.service.cancel("room-session-1", "user-1", "room-1")).isTrue();

        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.CANCELLED);
        verify(context.scheduler).cancel(execution.getExecutionId());
    }

    @Test
    void resultDoesNotCompleteExecutionAfterRoomSessionIsCancelled() throws Exception {
        TestContext context = new TestContext();
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch allowResult = new CountDownLatch(1);
        AtomicReference<ConversationStartContext> capturedContext = new AtomicReference<>();
        when(context.aiPort.generateSuggestion(any())).thenAnswer(invocation -> {
            ConversationStartContext aiContext = invocation.getArgument(0);
            capturedContext.set(aiContext);
            aiStarted.countDown();
            if (!allowResult.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to return AI result");
            }
            return new ConversationStartResult(aiContext.executionId(), List.of());
        });

        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(context.scheduler).schedule(any(), any(), taskCaptor.capture());

        Thread executionThread = new Thread(taskCaptor.getValue());
        executionThread.start();
        assertThat(aiStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(context.service.cancel("room-session-1", "user-1", "room-1")).isTrue();
        allowResult.countDown();
        executionThread.join(5_000);

        assertThat(executionThread.isAlive()).isFalse();
        assertThat(capturedContext.get().executionId()).isEqualTo(execution.getExecutionId());
        assertThat(capturedContext.get().roomSessionId()).isEqualTo("room-session-1");
        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.CANCELLED);
        assertThat(execution.getResult()).isNull();
    }

    @Test
    void completedResultIsRoutedUsingExecutionRoutingContext() {
        TestContext context = new TestContext();
        when(context.aiPort.generateSuggestion(any())).thenAnswer(invocation -> {
            ConversationStartContext aiContext = invocation.getArgument(0);
            return new ConversationStartResult(aiContext.executionId(), List.of());
        });

        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(context.scheduler).schedule(any(), any(), taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<ResultEvent> eventCaptor = ArgumentCaptor.forClass(ResultEvent.class);
        verify(context.resultRouter).route(org.mockito.ArgumentMatchers.eq(execution.getExecution()),
                eventCaptor.capture());
        assertThat(eventCaptor.getValue().executionId()).isEqualTo(execution.getExecutionId());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("CONVERSATION_START_COMPLETED");
        assertThat(execution.getExecution().getRoutingRef().tenantId()).isEqualTo("default");
        assertThat(execution.getExecution().getRoutingRef().clientSessionId())
                .isEqualTo("client-session-1");
        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.COMPLETED);
    }

    @Test
    void scheduledExecutionIsRejectedWhenTenantGlobalPolicyTurnsOffBeforeExecution() {
        TestContext context = new TestContext();
        // scheduler가 Mock 객체라면 실제 예약 작업은 실행되지 않으며, 예약 작업(호출은 함)을 제외한 로직만 점검
        // ① 실제 서비스 실행 → Mock scheduler.schedule() 호출 기록
        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");
        // ② schedule() 호출 여부 검증 + Runnable 캡처
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        // verify는 context.service.start내에서 스케쥴 메소드가 실제로 호출되었나?
        verify(context.scheduler).schedule(any(), any(), taskCaptor.capture());

        // default - false
        context.policySnapshot.set(AiPolicySnapshot.initialized(
                Map.of("default", false),
                Map.of(featureKey(), true),
                Instant.parse("2026-01-01T00:00:05Z")));

        // start로직 내부에 Runnable로 구현된 실행 검증
        // ③ 캡처한 Runnable 직접 실행
        taskCaptor.getValue().run();

        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.REJECTED);
        verify(context.aiPort, never()).generateSuggestion(any());
    }

    @Test
    void scheduledExecutionIsRejectedWhenFeaturePolicyTurnsOffBeforeExecution() {
        TestContext context = new TestContext();
        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(context.scheduler).schedule(any(), any(), taskCaptor.capture());
        context.policySnapshot.set(AiPolicySnapshot.initialized(
                Map.of("default", true),
                Map.of(featureKey(), false),
                Instant.parse("2026-01-01T00:00:05Z")));

        taskCaptor.getValue().run();

        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.REJECTED);
        verify(context.aiPort, never()).generateSuggestion(any());
    }

    @Test
    void scheduledExecutionIsRejectedWhenOverallUserPolicyIsDisabled() {
        TestContext context = new TestContext();
        when(context.userPolicyProvider.resolve("default", "user-1"))
                .thenReturn(UserAiPolicyResolution.resolved(UserAiPolicyStatus.DISABLED));
        ConversationStart execution = context.service.start(
                "room-session-1", "client-session-1", "user-1", "room-1", "chat");
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(context.scheduler).schedule(any(), any(), taskCaptor.capture());

        taskCaptor.getValue().run();

        assertThat(execution.getStatus()).isEqualTo(AiExecutionStatus.REJECTED);
        verify(context.aiPort, never()).generateSuggestion(any());
    }

    private static final class TestContext {
        private final InMemoryConversationStartRepository repository =
                new InMemoryConversationStartRepository();
        private final ConversationStartScheduler scheduler = mock(ConversationStartScheduler.class);
        private final ConversationStartAiPort aiPort = mock(ConversationStartAiPort.class);
        private final AiResultRouter resultRouter = mock(AiResultRouter.class);
        private final AtomicReference<AiPolicySnapshot> policySnapshot =
                new AtomicReference<>(AiPolicySnapshot.initialized(
                        Map.of("default", true),
                        Map.of(featureKey(), true),
                        Instant.parse("2026-01-01T00:00:00Z")));
        private final UserAiPolicyProvider userPolicyProvider =
                mock(UserAiPolicyProvider.class);
        private final AiExecutionPolicyService policyService =
                new AiExecutionPolicyService(
                        policySnapshot::get,
                        userPolicyProvider);
        private final ConversationStartService service = new ConversationStartService(
                repository,
                scheduler,
                new ConversationStartDelayPolicy(),
                new ConversationStartProperties(true, Duration.ofSeconds(10), Duration.ofMinutes(5)),
                aiPort,
                resultRouter,
                new RoutingRefFactory(new ResultRouterProperties(
                        "default", new ResultRouterProperties.Delivery(
                                Map.of(), "/internal/v1/ai-results",
                                Duration.ofSeconds(3), Duration.ofSeconds(5)))),
                policyService);

        private TestContext() {
                when(userPolicyProvider.resolve(anyString(), anyString()))
                        .thenReturn(UserAiPolicyResolution.resolved(
                                UserAiPolicyStatus.ENABLED));
        }
    }

    private static FeaturePolicyKey featureKey() {
        return new FeaturePolicyKey("default", AiFeature.CONVERSATION_START);
    }
}
