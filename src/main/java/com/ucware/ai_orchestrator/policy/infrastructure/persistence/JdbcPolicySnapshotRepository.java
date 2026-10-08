package com.ucware.ai_orchestrator.policy.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;

@Repository
public class JdbcPolicySnapshotRepository implements PolicySnapshotRepository {

    private final JdbcClient jdbcClient;

    public JdbcPolicySnapshotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Map<String, Boolean> findAllGlobalPolicies() {
        List<GlobalPolicyRow> rows = jdbcClient.sql("""
                        SELECT tenant_id, enabled
                        FROM ai_global_policy
                        """)
                .query((resultSet, rowNumber) -> new GlobalPolicyRow(
                        resultSet.getString("tenant_id"),
                        resultSet.getBoolean("enabled")))
                .list();

        Map<String, Boolean> policies = new LinkedHashMap<>();
        for (GlobalPolicyRow row : rows) {
            if (row.tenantId() == null || row.tenantId().isBlank()) {
                throw new IllegalStateException("Global Policy tenantId must not be blank");
            }
            Boolean previous = policies.putIfAbsent(row.tenantId(), row.enabled());
            if (previous != null) {
                throw new IllegalStateException("Duplicate Global Policy for tenant: " + row.tenantId());
            }
        }
        return Map.copyOf(policies);
    }

    private record GlobalPolicyRow(String tenantId, boolean enabled) {
    }
}
