package com.ucware.ai_orchestrator.policy.infrastructure.memory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.policy.domain.AiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshot;

@Component
public class InMemoryAiPolicyStore implements AiPolicyProvider {

    private final AtomicReference<AiPolicySnapshot> current =
            new AtomicReference<>(AiPolicySnapshot.uninitialized());

    @Override
    public AiPolicySnapshot currentSnapshot() {
        return current.get();
    }

    public void replace(AiPolicySnapshot snapshot) {
        current.set(Objects.requireNonNull(snapshot));
    }
}
