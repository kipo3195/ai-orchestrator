package com.ucware.ai_orchestrator.result.domain;

import java.util.Objects;

public record RoutingScope(String type, String id) {

    public RoutingScope {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
    }
}
