package com.ucware.ai_orchestrator.policy.domain;

import java.util.Arrays;

public enum AiFeature {

    CONVERSATION_START("conversation-start");

    private final String key;

    AiFeature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static AiFeature fromKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("featureKey must not be blank");
        }

        return Arrays.stream(values())
                .filter(feature -> feature.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported AI feature: " + key));
    }
}
