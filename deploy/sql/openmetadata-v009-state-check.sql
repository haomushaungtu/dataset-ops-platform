-- Read-only check for a partially applied OpenMetadata PostgreSQL migration 0.0.9.
-- Run only against the dedicated openmetadata_poc database.
\set ON_ERROR_STOP on

SELECT current_database() AS database_name;
SELECT count(*) AS v009_ledger_rows
FROM server_change_log
WHERE version = '0.0.9';

SELECT object_name, object_exists
FROM (
  VALUES
    ('storage_container_entity', to_regclass('public.storage_container_entity') IS NOT NULL),
    ('test_connection_definition', to_regclass('public.test_connection_definition') IS NOT NULL),
    ('automations_workflow', to_regclass('public.automations_workflow') IS NOT NULL),
    ('query_entity', to_regclass('public.query_entity') IS NOT NULL),
    ('temp_query_migration', to_regclass('public.temp_query_migration') IS NOT NULL),
    ('event_subscription_entity', to_regclass('public.event_subscription_entity') IS NOT NULL)
) AS state(object_name, object_exists)
ORDER BY object_name;

SELECT c.conname, pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
JOIN pg_class t ON t.oid = c.conrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'public'
  AND t.relname = 'user_entity'
  AND c.contype = 'u'
  AND pg_get_constraintdef(c.oid) = 'UNIQUE (email)'
ORDER BY c.conname;
