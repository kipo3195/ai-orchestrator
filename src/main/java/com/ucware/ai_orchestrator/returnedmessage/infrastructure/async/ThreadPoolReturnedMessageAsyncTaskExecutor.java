package com.ucware.ai_orchestrator.returnedmessage.infrastructure.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.returnedmessage.application.port.ReturnedMessageAsyncTaskExecutor;
import com.ucware.ai_orchestrator.returnedmessage.infrastructure.config.ReturnedMessageAsyncProperties;

@Component
public class ThreadPoolReturnedMessageAsyncTaskExecutor
        implements ReturnedMessageAsyncTaskExecutor, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(
            ThreadPoolReturnedMessageAsyncTaskExecutor.class);

    private final ThreadPoolTaskExecutor executionExecutor;
    private final ThreadPoolTaskExecutor deliveryExecutor;

    public ThreadPoolReturnedMessageAsyncTaskExecutor(ReturnedMessageAsyncProperties properties) {
        this.executionExecutor = executor(
                properties.executionPoolSize(),
                properties.queueCapacity(),
                "returned-message-execution-");
        this.deliveryExecutor = executor(
                properties.deliveryPoolSize(),
                properties.queueCapacity(),
                "returned-message-delivery-");
    }

    @Override
    public boolean submitExecution(Runnable task) {
        return submit(executionExecutor, task, "execution");
    }

    @Override
    public boolean submitDelivery(Runnable task) {
        return submit(deliveryExecutor, task, "delivery");
    }

    @Override
    public void destroy() {
        executionExecutor.shutdown();
        deliveryExecutor.shutdown();
    }

    private boolean submit(
            ThreadPoolTaskExecutor executor,
            Runnable task,
            String taskType) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException exception) {
            logger.warn("Returned Message {} task queue is full; recovery will retry", taskType);
            return false;
        }
    }

    private static ThreadPoolTaskExecutor executor(
            int poolSize,
            int queueCapacity,
            String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
