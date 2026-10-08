package com.ucware.ai_orchestrator.policy.infrastructure.memory;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicyProvider;
import com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshot;

@Component
public class InMemoryGlobalAiPolicyStore implements GlobalAiPolicyProvider {

    // AtomicReference 여러 스레드가 하나의 객체 참조를 안전하게 읽고 변경할 수 있도록 제공하는 클래스
    // 참조를 원자적으로 변경할 뿐, 참조된 객체 내부의 모든 변경까지 스레드 안전하게 만들어주지는 않는다.
    private final AtomicReference<GlobalAiPolicySnapshot> current =
            new AtomicReference<>(GlobalAiPolicySnapshot.uninitialized());

    @Override
    public GlobalAiPolicySnapshot currentSnapshot() {
        return current.get();
    }

    public void replace(GlobalAiPolicySnapshot snapshot) {
        current.set(snapshot);
    }
}
