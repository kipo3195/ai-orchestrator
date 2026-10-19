CREATE TABLE IF NOT EXISTS ai_global_policy (
    tenant_id VARCHAR(100) PRIMARY KEY,
    enabled BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_feature_policy (
    tenant_id VARCHAR(100) NOT NULL,
    feature_key VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, feature_key)
);

CREATE TABLE IF NOT EXISTS ai_user_policy (
    tenant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE IF NOT EXISTS returned_message_request (
    tenant_id VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    trigger_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    absence_period_id VARCHAR(100) NOT NULL,
    offline_since TIMESTAMP WITH TIME ZONE NOT NULL,
    returned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trigger_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    target_ref VARCHAR(200) NOT NULL,
    connection_type INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    message_selection_ref VARCHAR(300),
    unread_count INTEGER,
    selected_at TIMESTAMP WITH TIME ZONE,
    selection_expires_at TIMESTAMP WITH TIME ZONE,
    confirmation_id VARCHAR(100),
    confirmation_delivery_status VARCHAR(20),
    confirmation_expires_at TIMESTAMP WITH TIME ZONE,
    execution_id VARCHAR(100),
    result_ref VARCHAR(500),
    delivery_id VARCHAR(100),
    failure_stage VARCHAR(40),
    failure_reason VARCHAR(200),
    claim_owner VARCHAR(100),
    claim_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT uq_returned_message_absence
        UNIQUE (tenant_id, user_id, absence_period_id),
    CONSTRAINT uq_returned_message_trigger
        UNIQUE (tenant_id, trigger_id)
);

CREATE INDEX IF NOT EXISTS idx_returned_message_expiration
    ON returned_message_request (status, confirmation_expires_at);

CREATE INDEX IF NOT EXISTS idx_returned_message_recovery
    ON returned_message_request (status, claim_until, updated_at);
