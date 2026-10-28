package com.ucware.ai_orchestrator.returnedmessage.application.port;

public interface ReturnedMessageAsyncTaskExecutor {

    boolean submitExecution(Runnable task);

    boolean submitDelivery(Runnable task);
}
