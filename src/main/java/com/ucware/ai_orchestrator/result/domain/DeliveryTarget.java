package com.ucware.ai_orchestrator.result.domain;

import java.util.Objects;

public record DeliveryTarget(String connectionId, String ownerInstanceId) {

    public DeliveryTarget {
        Objects.requireNonNull(connectionId);
        Objects.requireNonNull(ownerInstanceId);
    }
}
