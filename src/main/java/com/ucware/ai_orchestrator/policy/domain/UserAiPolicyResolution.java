package com.ucware.ai_orchestrator.policy.domain;

import java.util.Objects;

public sealed interface UserAiPolicyResolution
        permits UserAiPolicyResolution.Resolved, UserAiPolicyResolution.Unavailable {

    record Resolved(UserAiPolicyStatus status) implements UserAiPolicyResolution {

        public Resolved {
            Objects.requireNonNull(status);
        }
    }

    record Unavailable() implements UserAiPolicyResolution {
    }

    static UserAiPolicyResolution resolved(UserAiPolicyStatus status) {
        return new Resolved(status);
    }

    static UserAiPolicyResolution unavailable() {
        return new Unavailable();
    }
}
