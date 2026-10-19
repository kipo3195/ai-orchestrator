package com.ucware.ai_orchestrator.returnedmessage.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageContractConflictException;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageNotFoundException;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePersistenceException;
import com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessagePolicyUnavailableException;

@RestControllerAdvice(assignableTypes = ReturnedMessageController.class)
public class ReturnedMessageExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request body");
        return problem(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request");
    }

    @ExceptionHandler(ReturnedMessageContractConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(ReturnedMessageContractConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Returned Message correlation conflict");
    }

    @ExceptionHandler(ReturnedMessageNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(ReturnedMessageNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Returned Message lifecycle not found");
    }

    @ExceptionHandler(ReturnedMessagePersistenceException.class)
    public ResponseEntity<ProblemDetail> unavailable(ReturnedMessagePersistenceException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Returned Message lifecycle is unavailable");
    }

    @ExceptionHandler(ReturnedMessagePolicyUnavailableException.class)
    public ResponseEntity<ProblemDetail> policyUnavailable(
            ReturnedMessagePolicyUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Returned Message policy is unavailable");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).body(body);
    }
}
