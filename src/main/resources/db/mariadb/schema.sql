
CREATE TABLE IF NOT EXISTS ai_global_policy (
    tenant_id VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_feature_policy (
    tenant_id VARCHAR(100) NOT NULL,
    feature_key VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, feature_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_user_policy (
    tenant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS returned_message_request (
    tenant_id VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    trigger_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    absence_period_id VARCHAR(100) NOT NULL,
    offline_since DATETIME(6) NOT NULL,
    returned_at DATETIME(6) NOT NULL,
    trigger_expires_at DATETIME(6) NOT NULL,
    target_ref VARCHAR(200) NOT NULL,
    connection_type INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    message_selection_ref VARCHAR(300),
    unread_count INTEGER,
    selected_at DATETIME(6),
    selection_expires_at DATETIME(6),
    confirmation_id VARCHAR(100),
    confirmation_delivery_status VARCHAR(20),
    confirmation_expires_at DATETIME(6),
    execution_id VARCHAR(100),
    result_ref VARCHAR(500),
    delivery_id VARCHAR(100),
    failure_stage VARCHAR(40),
    failure_reason VARCHAR(200),
    claim_owner VARCHAR(100),
    claim_until DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (tenant_id, request_id),

    CONSTRAINT uq_returned_message_absence
        UNIQUE (tenant_id, user_id, absence_period_id),

    CONSTRAINT uq_returned_message_trigger
        UNIQUE (tenant_id, trigger_id),

    INDEX idx_returned_message_expiration
        (status, confirmation_expires_at),

    INDEX idx_returned_message_recovery
        (status, claim_until, updated_at)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS returned_message_selection (
    selection_ref VARCHAR(36) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    absence_period_id VARCHAR(100) NOT NULL,
    selected_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (selection_ref),

    CONSTRAINT uq_returned_message_selection_request
        UNIQUE (tenant_id, request_id),

    CONSTRAINT fk_returned_message_selection_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES returned_message_request (tenant_id, request_id)
        ON DELETE CASCADE,

    INDEX idx_returned_message_selection_expiration
        (expires_at)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS returned_message_selection_item (
    selection_ref VARCHAR(36) NOT NULL,
    sequence_no INTEGER NOT NULL,
    source_system VARCHAR(50) NOT NULL,
    source_item_type VARCHAR(40) NOT NULL,
    source_item_key VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (selection_ref, sequence_no),

    CONSTRAINT uq_returned_message_selection_item
        UNIQUE (
            selection_ref,
            source_system,
            source_item_type,
            source_item_key
        ),

    CONSTRAINT fk_returned_message_selection_item
        FOREIGN KEY (selection_ref)
        REFERENCES returned_message_selection (selection_ref)
        ON DELETE CASCADE

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
