-- DEPRECATED: do not delete objects from a partially checkpointed OpenMetadata migration.
-- OpenMetadata records every successful statement in server_migration_sql_logs.
-- Deleting those objects does not remove the checkpoints, so an official rerun skips
-- their CREATE statements and leaves the database inconsistent.
--
-- Safe recovery: restore a backup from before the destructive repair (or preserve the
-- checkpointed objects), install/enable pgcrypto and pg_trgm, then rerun the official
-- OpenMetadata migration. This file intentionally fails before changing any object.
\set ON_ERROR_STOP on

DO $deprecated$
BEGIN
  RAISE EXCEPTION USING
    MESSAGE = 'openmetadata-repair-partial-v009.sql is deprecated and intentionally makes no changes',
    HINT = 'Restore the pre-repair backup, enable pgcrypto and pg_trgm, and rerun the official OpenMetadata migration.';
END
$deprecated$;
