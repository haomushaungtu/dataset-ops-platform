CREATE SCHEMA IF NOT EXISTS business;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS integration;
CREATE SCHEMA IF NOT EXISTS workflow;

CREATE TABLE business.supplier_application (
    id UUID PRIMARY KEY,
    application_no VARCHAR(40) NOT NULL UNIQUE,
    applicant_id VARCHAR(128) NOT NULL,
    organization_name VARCHAR(200) NOT NULL,
    unified_social_credit_code VARCHAR(18) NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    contact_phone VARCHAR(40) NOT NULL,
    organization_snapshot TEXT NOT NULL,
    submitted_snapshot TEXT,
    status VARCHAR(32) NOT NULL,
    process_instance_id VARCHAR(128),
    review_comment VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_supplier_application_status CHECK (
        status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','RETURNED','APPROVED','REJECTED','WITHDRAWN')
    )
);

CREATE INDEX idx_supplier_application_applicant ON business.supplier_application (applicant_id, updated_at);
CREATE INDEX idx_supplier_application_status ON business.supplier_application (status, updated_at);

CREATE TABLE business.supplier_application_state_history (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES business.supplier_application(id),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    reason VARCHAR(2000),
    request_id VARCHAR(128) NOT NULL,
    business_version BIGINT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_supplier_history_application ON business.supplier_application_state_history (application_id, occurred_at);

CREATE TABLE business.supplier (
    id UUID PRIMARY KEY,
    supplier_no VARCHAR(40) NOT NULL UNIQUE,
    application_id UUID NOT NULL UNIQUE REFERENCES business.supplier_application(id),
    organization_name VARCHAR(200) NOT NULL,
    unified_social_credit_code VARCHAR(18) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','DISABLED')),
    identity_sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        identity_sync_status IN ('PENDING','SYNCED','FAILED')
    ),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE business.supplier_member (
    supplier_id UUID NOT NULL REFERENCES business.supplier(id),
    user_id VARCHAR(128) NOT NULL,
    member_role VARCHAR(20) NOT NULL CHECK (member_role IN ('OWNER','MEMBER')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (supplier_id, user_id)
);

CREATE TABLE audit.audit_event (
    event_id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(128) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id UUID NOT NULL,
    before_state VARCHAR(64),
    after_state VARCHAR(64),
    reason VARCHAR(2000),
    request_id VARCHAR(128) NOT NULL,
    business_version BIGINT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_object ON audit.audit_event (object_type, object_id, occurred_at);
CREATE INDEX idx_audit_actor ON audit.audit_event (actor_id, occurred_at);

CREATE TABLE integration.outbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_unpublished ON integration.outbox_event (published_at, next_attempt_at, occurred_at);

CREATE TABLE integration.idempotency_record (
    actor_id VARCHAR(128) NOT NULL,
    operation_scope VARCHAR(240) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (actor_id, operation_scope, idempotency_key)
);
