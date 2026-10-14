package com.ucware.ai_orchestrator.policy.infrastructure.persistence;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ucware.ai_orchestrator.policy.application.port.UserAiPolicyRepository;
import com.ucware.ai_orchestrator.policy.domain.UserAiPolicy;

@Repository
public class JdbcUserAiPolicyRepository implements UserAiPolicyRepository {

    private final JdbcClient jdbcClient;

    public JdbcUserAiPolicyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<UserAiPolicy> findByTenantIdAndUserId(String tenantId, String userId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, user_id, enabled
                        FROM ai_user_policy
                        WHERE tenant_id = :tenantId
                          AND user_id = :userId
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .query((resultSet, rowNumber) -> {
                    Boolean enabled = resultSet.getObject("enabled", Boolean.class);
                    if (enabled == null) {
                        throw new IllegalStateException("User AI policy enabled must not be null");
                    }
                    return new UserAiPolicy(
                            resultSet.getString("tenant_id"),
                            resultSet.getString("user_id"),
                            enabled);
                })
                .optional();
    }
}
