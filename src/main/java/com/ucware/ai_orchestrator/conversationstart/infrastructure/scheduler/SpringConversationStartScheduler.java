package com.ucware.ai_orchestrator.conversationstart.infrastructure.scheduler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.conversationstart.application.port.ConversationStartScheduler;

@Component
public class SpringConversationStartScheduler implements ConversationStartScheduler {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public synchronized void schedule(String executionId, Duration delay, Runnable task) {
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                task.run();
            } finally {
                tasks.remove(executionId);
            }
        },
                Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = tasks.put(executionId, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    @Override
    public synchronized void cancel(String executionId) {
        ScheduledFuture<?> future = tasks.remove(executionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        tasks.values().forEach(future -> future.cancel(false));
        tasks.clear();
        executor.shutdown();
    }
}
