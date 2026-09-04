CREATE TABLE integration.idempotency_lock (
    actor_id VARCHAR(128) NOT NULL,
    operation_scope VARCHAR(240) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (actor_id, operation_scope, idempotency_key)
);
