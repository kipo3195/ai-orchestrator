package com.ucware.ai_orchestrator.result.infrastructure.client;

import com.ucware.ai_orchestrator.result.application.port.ResultDeliveryPort;
import com.ucware.ai_orchestrator.result.domain.DeliveryEvent;
import com.ucware.ai_orchestrator.result.domain.DeliveryTarget;

/**
 * Placeholder for the future NATS-based result delivery implementation.
 *
 * <p>This class is intentionally not registered as a Spring bean while REST is
 * the active delivery mechanism.</p>
 */
public final class NatsResultDeliveryClient implements ResultDeliveryPort {

    @Override
    public void deliver(DeliveryTarget target, DeliveryEvent event) {
        throw new UnsupportedOperationException("NATS result delivery is not implemented");
    }
}
