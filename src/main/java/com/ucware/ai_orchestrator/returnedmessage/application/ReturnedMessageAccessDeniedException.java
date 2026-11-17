package com.ucware.ai_orchestrator.returnedmessage.application;

public class ReturnedMessageAccessDeniedException extends RuntimeException {
    public ReturnedMessageAccessDeniedException(String message) {
        super(message);
    }
}
