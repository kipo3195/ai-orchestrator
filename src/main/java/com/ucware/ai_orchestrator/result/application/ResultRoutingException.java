package com.ucware.ai_orchestrator.result.application;

public class ResultRoutingException extends RuntimeException {

    public ResultRoutingException(String message) {
        super(message);
    }

    public ResultRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
