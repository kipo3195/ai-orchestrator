package com.ucware.ai_orchestrator.policy.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserAiPolicyTests {

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new UserAiPolicy("  ", "user-1", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId must not be blank");
    }

    @Test
    void rejectsBlankUserId() {
        assertThatThrownBy(() -> new UserAiPolicy("tenant-1", "  ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be blank");
    }
}
