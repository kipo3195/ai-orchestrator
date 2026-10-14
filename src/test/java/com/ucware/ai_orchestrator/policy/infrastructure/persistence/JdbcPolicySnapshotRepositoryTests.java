package com.ucware.ai_orchestrator.policy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ucware.ai_orchestrator.policy.domain.AiFeature;
import com.ucware.ai_orchestrator.policy.domain.FeaturePolicyKey;

@JdbcTest
@Import(JdbcPolicySnapshotRepository.class)
class JdbcPolicySnapshotRepositoryTests {

    @Autowired
    private JdbcPolicySnapshotRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void loadsGlobalAndFeaturePoliciesTogether() {
        jdbcTemplate.update(
                "INSERT INTO ai_global_policy (tenant_id, enabled) VALUES (?, ?)",
                "tenant-1", true);
        jdbcTemplate.update(
                "INSERT INTO ai_feature_policy (tenant_id, feature_key, enabled) VALUES (?, ?, ?)",
                "tenant-1", AiFeature.CONVERSATION_START.key(), false);

        var data = repository.findAllPolicies();

        assertThat(data.globalPolicies()).containsExactlyEntriesOf(Map.of("tenant-1", true));
        assertThat(data.featurePolicies()).containsExactlyEntriesOf(Map.of(
                new FeaturePolicyKey("tenant-1", AiFeature.CONVERSATION_START), false));
    }

    @Test
    void returnsEmptyCollectionsWhenPoliciesDoNotExist() {
        var data = repository.findAllPolicies();

        assertThat(data.globalPolicies()).isEmpty();
        assertThat(data.featurePolicies()).isEmpty();
    }
}
