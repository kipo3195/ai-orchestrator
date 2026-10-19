package com.ucware.ai_orchestrator.returnedmessage.application;

public class ReturnedMessagePolicyUnavailableException extends RuntimeException {

    public ReturnedMessagePolicyUnavailableException(String reason) {
        super(reason);
    }
}
