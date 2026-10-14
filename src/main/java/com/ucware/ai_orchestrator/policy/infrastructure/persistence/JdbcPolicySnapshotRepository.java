package com.ucware.ai_orchestrator.policy.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotData;
import com.ucware.ai_orchestrator.policy.application.port.PolicySnapshotRepository;
import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;

@Repository
public class JdbcPolicySnapshotRepository implements PolicySnapshotRepository {

    private final JdbcClient jdbcClient;

    public JdbcPolicySnapshotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicySnapshotData findAllPolicies() {
        List<GlobalPolicyRow> rows = jdbcClient.sql("""
                        SELECT tenant_id, enabled
                        FROM ai_global_policy
                        """)
                .query((resultSet, rowNumber) -> {
                    Boolean enabled = resultSet.getObject("enabled", Boolean.class);
                    if (enabled == null) {
                        throw new IllegalStateException("Global Policy enabled must not be null");
                    }
                    return new GlobalPolicyRow(resultSet.getString("tenant_id"), enabled);
                })
                .list();

        Map<String, Boolean> globalPolicies = new LinkedHashMap<>();
        for (GlobalPolicyRow row : rows) {
            if (row.tenantId() == null || row.tenantId().isBlank()) {
                throw new IllegalStateException("Global Policy tenantId must not be blank");
            }
            Boolean previous = globalPolicies.putIfAbsent(row.tenantId(), row.enabled());
            if (previous != null) {
                throw new IllegalStateException("Duplicate Global Policy for tenant: " + row.tenantId());
            }
        }

        List<FeaturePolicyRow> featureRows = jdbcClient.sql("""
                        SELECT tenant_id, feature_key, enabled
                        FROM ai_feature_policy
                        """)
                .query((resultSet, rowNumber) -> {
                    Boolean enabled = resultSet.getObject("enabled", Boolean.class);
                    if (enabled == null) {
                        throw new IllegalStateException("Feature Policy enabled must not be null");
                    }
                    return new FeaturePolicyRow(
                            resultSet.getString("tenant_id"),
                            resultSet.getString("feature_key"),
                            enabled);
                })
                .list();

        Map<FeaturePolicyKey, Boolean> featurePolicies = new LinkedHashMap<>();
        for (FeaturePolicyRow row : featureRows) {
            FeaturePolicyKey key;
            try {
                key = new FeaturePolicyKey(row.tenantId(), AiFeature.fromKey(row.featureKey()));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalStateException("Invalid Feature Policy identifier", exception);
            }
            Boolean previous = featurePolicies.putIfAbsent(key, row.enabled());
            if (previous != null) {
                throw new IllegalStateException("Duplicate Feature Policy for key: " + key);
            }
        }

        return new PolicySnapshotData(globalPolicies, featurePolicies);
    }

    private record GlobalPolicyRow(String tenantId, boolean enabled) {
    }

    private record FeaturePolicyRow(String tenantId, String featureKey, boolean enabled) {
    }
}
