package com.ucware.ai_orchestrator.returnedmessage.application;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageRequest;

public interface HandleUserReturned {
    ReturnedMessageOperationResult handle(ReturnedMessageRequest request);
}
