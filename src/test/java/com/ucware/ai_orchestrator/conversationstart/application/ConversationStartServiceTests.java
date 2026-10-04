package com.ucware.ai_orchestrator.conversationstart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
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

    private static final class TestContext {
        private final InMemoryConversationStartRepository repository =
                new InMemoryConversationStartRepository();
        private final ConversationStartScheduler scheduler = mock(ConversationStartScheduler.class);
        private final ConversationStartAiPort aiPort = mock(ConversationStartAiPort.class);
        private final AiResultRouter resultRouter = mock(AiResultRouter.class);
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
                                Duration.ofSeconds(3), Duration.ofSeconds(5)))));
    }
}
