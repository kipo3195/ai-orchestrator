CREATE TABLE IF NOT EXISTS ai_global_policy (
    tenant_id VARCHAR(100) PRIMARY KEY,
    enabled BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_user_policy (
    tenant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);
