CREATE TABLE IF NOT EXISTS ai_global_policy (
    tenant_id VARCHAR(100) PRIMARY KEY,
    enabled BOOLEAN NOT NULL
);
