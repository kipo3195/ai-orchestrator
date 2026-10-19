package com.ucware.ai_orchestrator.returnedmessage.application;

public class ReturnedMessageNotFoundException extends RuntimeException {
    public ReturnedMessageNotFoundException() {
        super("Returned Message lifecycle not found");
    }
}
