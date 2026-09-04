-- Run only after the PostgreSQL 16 server package providing pgcrypto.control is installed.
-- Execute against the dedicated openmetadata_poc database as an authorized DBA or owner.
\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT extname, extversion FROM pg_extension WHERE extname = 'pgcrypto';
