ALTER TABLE integration.outbox_event
    ADD COLUMN failed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE integration.outbox_event
    ADD COLUMN last_error_code VARCHAR(64);

ALTER TABLE integration.outbox_event
    ADD COLUMN last_error_message VARCHAR(500);

CREATE TABLE integration.external_resource_mapping (
    mapping_id UUID PRIMARY KEY,
    external_system VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    business_id UUID NOT NULL,
    external_id VARCHAR(256),
    external_fqn VARCHAR(1024) NOT NULL,
    external_version VARCHAR(128),
    sync_status VARCHAR(20) NOT NULL CHECK (sync_status IN ('PENDING','SYNCED','FAILED')),
    source_event_id UUID NOT NULL UNIQUE REFERENCES integration.outbox_event(event_id),
    last_synced_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_external_mapping_business UNIQUE (external_system, resource_type, business_id),
    CONSTRAINT uq_external_mapping_external UNIQUE (
        external_system, resource_type, external_id, external_version
    )
);

CREATE INDEX idx_external_mapping_sync_status
    ON integration.external_resource_mapping (external_system, sync_status, updated_at);

CREATE TABLE integration.outbox_delivery_attempt (
    attempt_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES integration.outbox_event(event_id),
    attempt_no INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (
        outcome IN ('SUCCEEDED','RETRY_SCHEDULED','TERMINAL_FAILED')
    ),
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_outbox_delivery_attempt UNIQUE (event_id, attempt_no)
);

CREATE INDEX idx_outbox_delivery_attempt_event
    ON integration.outbox_delivery_attempt (event_id, attempt_no);
