package com.ucware.ai_orchestrator.returnedmessage.application;

import com.ucware.ai_orchestrator.returnedmessage.domain.ReturnedMessageConfirmation;

public interface HandleReturnedMessageConfirmation {
    ReturnedMessageOperationResult handle(ReturnedMessageConfirmation confirmation);
}
