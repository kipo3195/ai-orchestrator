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
    confirmation_expires_at TIMESTAMP WITH TIME ZONE,
    execution_id VARCHAR(100),
    result_payload TEXT,
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

CREATE TABLE IF NOT EXISTS returned_message_selection (
    selection_ref VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    absence_period_id VARCHAR(100) NOT NULL,
    selected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_returned_message_selection_request
        UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_returned_message_selection_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES returned_message_request (tenant_id, request_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_returned_message_selection_expiration
    ON returned_message_selection (expires_at);

CREATE TABLE IF NOT EXISTS returned_message_selection_item (
    selection_ref VARCHAR(36) NOT NULL,
    sequence_no INTEGER NOT NULL,
    source_system VARCHAR(50) NOT NULL,
    source_item_type VARCHAR(40) NOT NULL,
    source_item_key VARCHAR(200) NOT NULL,
    source_item_subject TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (selection_ref, sequence_no),
    CONSTRAINT uq_returned_message_selection_item
        UNIQUE (selection_ref, source_system, source_item_type, source_item_key),
    CONSTRAINT fk_returned_message_selection_item
        FOREIGN KEY (selection_ref)
        REFERENCES returned_message_selection (selection_ref)
        ON DELETE CASCADE
);

ALTER TABLE returned_message_selection_item
    ADD COLUMN IF NOT EXISTS source_item_subject TEXT;

ALTER TABLE returned_message_request
    ADD COLUMN IF NOT EXISTS result_payload TEXT;
