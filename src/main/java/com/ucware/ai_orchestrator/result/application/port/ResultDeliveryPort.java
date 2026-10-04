package com.ucware.ai_orchestrator.result.application.port;

import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;

public interface ResultDeliveryPort {

    void deliver(DeliveryTarget target, DeliveryEvent event);
}
