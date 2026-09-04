-- Read-only preflight for the OpenMetadata 1.13 PostgreSQL database.
SELECT current_setting('server_version') AS server_version;
SELECT EXISTS (
  SELECT 1 FROM pg_available_extensions WHERE name = 'pgcrypto'
) AS pgcrypto_available;
SELECT EXISTS (
  SELECT 1 FROM pg_extension WHERE extname = 'pgcrypto'
) AS pgcrypto_installed;
SELECT has_database_privilege(current_user, current_database(), 'CREATE')
  AS current_role_can_create_in_database;
