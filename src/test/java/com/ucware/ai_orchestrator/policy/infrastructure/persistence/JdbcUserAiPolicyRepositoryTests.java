package com.ucware.ai_orchestrator.policy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ucware.ai_orchestrator.policy.domain.UserAiPolicy;

@JdbcTest
@Import(JdbcUserAiPolicyRepository.class)
class JdbcUserAiPolicyRepositoryTests {

    @Autowired
    private JdbcUserAiPolicyRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsPolicyByTenantAndUser() {
        jdbcTemplate.update(
                "INSERT INTO ai_user_policy (tenant_id, user_id, enabled) VALUES (?, ?, ?)",
                "tenant-1", "user-1", true);
        jdbcTemplate.update(
                "INSERT INTO ai_user_policy (tenant_id, user_id, enabled) VALUES (?, ?, ?)",
                "tenant-2", "user-1", false);

        var result = repository.findByTenantIdAndUserId("tenant-1", "user-1");

        assertThat(result).contains(new UserAiPolicy("tenant-1", "user-1", true));
    }

    @Test
    void returnsEmptyWhenPolicyDoesNotExist() {
        assertThat(repository.findByTenantIdAndUserId("tenant-1", "missing-user")).isEmpty();
    }
}
