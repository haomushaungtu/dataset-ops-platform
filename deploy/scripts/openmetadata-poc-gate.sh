#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only gate for the isolated OpenMetadata 1.13.0 PoC on 10.100.165.139.
# It never runs a migration, starts a process, or changes database/search state.

usage() {
  echo "usage: $0 <0600-runtime-env> [preflight|search|verify]" >&2
  exit 64
}

fail() {
  echo "OPENMETADATA_GATE=blocked"
  echo "BLOCKER=$1"
  exit "${2:-1}"
}

runtime_env=${1:-}
phase=${2:-preflight}
[ -n "$runtime_env" ] || usage
case "$phase" in
  preflight|search|verify) ;;
  *) usage ;;
esac

[ -f "$runtime_env" ] || fail runtime-env-missing 10
[ ! -L "$runtime_env" ] || fail runtime-env-must-not-be-symlink 11
env_mode=$(stat -Lc '%a' "$runtime_env")
[ "$env_mode" = 600 ] || fail runtime-env-mode-must-be-0600 12
env_owner=$(stat -Lc '%U' "$runtime_env")
case "$env_owner" in
  root|dfmetadata) ;;
  *) fail runtime-env-owner-must-be-root-or-dfmetadata 13 ;;
esac

set -a
# The file is trusted only after its owner and mode have been checked above.
# shellcheck disable=SC1090
. "$runtime_env"
set +a

required_vars=(
  JAVA_HOME OPENMETADATA_HOME SERVER_HOST SERVER_PORT SERVER_ADMIN_PORT
  DB_HOST DB_PORT OM_DATABASE DB_USER DB_USER_PASSWORD
  ELASTICSEARCH_HOST ELASTICSEARCH_PORT ELASTICSEARCH_SCHEME SEARCH_TYPE
)
for variable in "${required_vars[@]}"; do
  [ -n "${!variable:-}" ] || fail "missing-$variable" 14
done

[ "$OPENMETADATA_HOME" = /szah/dataset-foundry-poc/runtime/openmetadata-1.13.0 ] || fail unexpected-openmetadata-home 15
[ "$OM_DATABASE" = openmetadata_poc ] || fail database-is-not-dedicated-poc 16
[ "$SERVER_HOST" = 127.0.0.1 ] || fail application-host-is-not-loopback 17
[ "$SERVER_PORT" = 18585 ] || fail unexpected-application-port 18
[ "$SERVER_ADMIN_PORT" = 18586 ] || fail unexpected-admin-port 19
[ "$SEARCH_TYPE" = opensearch ] || fail search-type-is-not-opensearch 20
[ "$ELASTICSEARCH_HOST" = 127.0.0.1 ] || fail search-host-is-not-loopback 21
[ "$ELASTICSEARCH_PORT" = 19200 ] || fail search-port-is-not-isolated-poc 22
[ "$ELASTICSEARCH_SCHEME" = http ] || fail unexpected-poc-search-scheme 23
[ -x "$JAVA_HOME/bin/java" ] || fail java-runtime-missing 24
[ -x "$OPENMETADATA_HOME/bootstrap/openmetadata-ops.sh" ] || fail migration-tool-missing 25
[ -x "$OPENMETADATA_HOME/bin/openmetadata-server-start.sh" ] || fail server-launcher-missing 26

java_major=$(
  "$JAVA_HOME/bin/java" -version 2>&1 |
    awk -F '[".]' '/version/ { if ($2 == "1") print $3; else print $2; exit }'
)
[ "$java_major" = 21 ] || fail java-major-is-not-21 27
echo "JAVA_MAJOR=$java_major"

if ss -lntH | awk '{print $4}' | grep -Eq '(^|:)18983$'; then
  fail solr-must-be-stopped-before-opensearch 28
fi

openmetadata_process_running() {
  local pid
  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    if [ "$(readlink "/proc/$pid/cwd" 2>/dev/null || true)" = "$OPENMETADATA_HOME" ]; then
      return 0
    fi
  done < <(pgrep -u dfmetadata -x java 2>/dev/null || true)
  return 1
}

if [ "$phase" != verify ]; then
  if ss -lntH | awk '{print $4}' | grep -Eq '(^|:)(18585|18586)$'; then
    fail openmetadata-service-already-listening 29
  fi
  if openmetadata_process_running; then
    fail stale-openmetadata-process 30
  fi
fi

available_mb=$(free -m | awk '/^Mem:/ {print $7}')
[ -n "$available_mb" ] || fail available-memory-unknown 31
minimum_available_mb=3072
if [ "$phase" = verify ]; then
  minimum_available_mb=768
fi
[ "$available_mb" -ge "$minimum_available_mb" ] || fail "available-memory-below-${minimum_available_mb}mb" 32
echo "MEM_AVAILABLE_MB=$available_mb"

python_lib=/szah/dataset-foundry-poc/runtime/dataverse-python
[ -d "$python_lib" ] || fail psycopg2-runtime-missing 33
export GATE_PHASE="$phase"
PYTHONPATH="$python_lib" python3 - <<'PY'
import os
import sys

import psycopg2

conn = psycopg2.connect(
    host=os.environ["DB_HOST"],
    port=os.environ["DB_PORT"],
    dbname=os.environ["OM_DATABASE"],
    user=os.environ["DB_USER"],
    password=os.environ["DB_USER_PASSWORD"],
    connect_timeout=5,
)
conn.set_session(readonly=True, autocommit=True)
with conn.cursor() as cur:
    cur.execute("show server_version")
    server_version = cur.fetchone()[0]
    cur.execute("select exists(select 1 from pg_available_extensions where name='pgcrypto')")
    available = cur.fetchone()[0]
    cur.execute("select exists(select 1 from pg_extension where extname='pgcrypto')")
    installed = cur.fetchone()[0]
    cur.execute("select exists(select 1 from pg_available_extensions where name='pg_trgm')")
    pg_trgm_available = cur.fetchone()[0]
    cur.execute("select exists(select 1 from pg_extension where extname='pg_trgm')")
    pg_trgm_installed = cur.fetchone()[0]
    cur.execute("select has_database_privilege(current_user, current_database(), 'CREATE')")
    can_create = cur.fetchone()[0]
    cur.execute("select count(*) from information_schema.tables where table_schema='public'")
    table_count = cur.fetchone()[0]
    cur.execute("select to_regclass('public.server_change_log') is not null")
    ledger_exists = cur.fetchone()[0]
    v009_ledger_rows = 0
    if ledger_exists:
        cur.execute("select count(*) from server_change_log where version='0.0.9'")
        v009_ledger_rows = cur.fetchone()[0]
    cur.execute("select to_regclass('public.server_migration_sql_logs') is not null")
    sql_log_exists = cur.fetchone()[0]
    v009_sql_log_checksums = set()
    if sql_log_exists:
        cur.execute("select checksum from server_migration_sql_logs where version='0.0.9'")
        v009_sql_log_checksums = {row[0] for row in cur.fetchall()}
    partial_tables = (
        "storage_container_entity",
        "test_connection_definition",
        "automations_workflow",
        "query_entity",
        "temp_query_migration",
    )
    partial_objects = 0
    partial_table_rows = 0
    for table_name in partial_tables:
        cur.execute("select to_regclass(%s) is not null", ("public." + table_name,))
        table_exists = cur.fetchone()[0]
        partial_objects += int(table_exists)
        if table_exists:
            cur.execute("select count(*) from " + table_name)
            partial_table_rows += cur.fetchone()[0]
    email_constraint_count = 0
    if ledger_exists and table_count:
        cur.execute(
            "select count(*) from pg_constraint c "
            "join pg_class t on t.oid=c.conrelid "
            "join pg_namespace n on n.oid=t.relnamespace "
            "where n.nspname='public' and t.relname='user_entity' "
            "and c.conname='user_entity_email_key' and c.contype='u' "
            "and pg_get_constraintdef(c.oid)='UNIQUE (email)'"
        )
        email_constraint_count = cur.fetchone()[0]
        partial_objects += email_constraint_count
    cur.execute("select to_regclass('public.event_subscription_entity') is not null")
    event_subscription_exists = cur.fetchone()[0]
    update_target_rows = 0
    for table_name in ("dbservice_entity", "ingestion_pipeline_entity", "entity_extension"):
        cur.execute("select to_regclass(%s) is not null", ("public." + table_name,))
        if cur.fetchone()[0]:
            cur.execute("select count(*) from " + table_name)
            update_target_rows += cur.fetchone()[0]
conn.close()

expected_v009_pre_extension_checksums = {
    "2f9b1ec0491ab075cc504271952ecadb",
    "c05754804eed080fe8a82165cf5f040f",
    "bde379bf7b50a27b13ea72e0f177e257",
    "7884bbde2d39e2a4c210d8fb07fe864f",
    "d9b0e4284369a42d6a02cb19bf06a15f",
    "310121201834dbbb464156350c5ce964",
    "415fb98fea61d08b89bcfcc51f741ae3",
    "e9945c76408e4e865642be18695e4566",
    "0134fec2749c84341a1120ce095444cc",
}
resumable_v009_checkpoint = (
    v009_ledger_rows == 0
    and partial_objects == 6
    and partial_table_rows == 0
    and email_constraint_count == 1
    and not event_subscription_exists
    and update_target_rows == 0
    and v009_sql_log_checksums == expected_v009_pre_extension_checksums
)

print(f"PG_SERVER_VERSION={server_version}")
print(f"PGCRYPTO_AVAILABLE={str(available).lower()}")
print(f"PGCRYPTO_INSTALLED={str(installed).lower()}")
print(f"PG_TRGM_AVAILABLE={str(pg_trgm_available).lower()}")
print(f"PG_TRGM_INSTALLED={str(pg_trgm_installed).lower()}")
print(f"OPENMETADATA_ROLE_CAN_CREATE={str(can_create).lower()}")
print(f"OPENMETADATA_PUBLIC_TABLES={table_count}")
print(f"V009_LEDGER_ROWS={v009_ledger_rows}")
print(f"V009_PRE_LEDGER_OBJECTS={partial_objects}")
print(f"V009_SQL_LOG_ROWS={len(v009_sql_log_checksums)}")
print(f"V009_RESUMABLE_CHECKPOINT={str(resumable_v009_checkpoint).lower()}")
if not server_version.startswith("16."):
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=postgresql-major-is-not-16")
    sys.exit(34)
if v009_ledger_rows == 0:
    if partial_objects and not resumable_v009_checkpoint:
        print("OPENMETADATA_GATE=blocked")
        print("BLOCKER=inconsistent-partial-v009-state")
        sys.exit(44)
    if not partial_objects and v009_sql_log_checksums:
        print("OPENMETADATA_GATE=blocked")
        print("BLOCKER=v009-sql-checkpoints-without-required-objects")
        sys.exit(44)
if v009_ledger_rows > 1:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=duplicate-v009-ledger-state")
    sys.exit(45)
if not available:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=pgcrypto-server-package-unavailable")
    sys.exit(35)
if not installed:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=pgcrypto-not-enabled-in-openmetadata-poc")
    sys.exit(36)
if not pg_trgm_available:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=pg-trgm-server-package-unavailable")
    sys.exit(46)
if not pg_trgm_installed:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=pg-trgm-not-enabled-in-openmetadata-poc")
    sys.exit(47)
if not can_create:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=openmetadata-role-lacks-database-create")
    sys.exit(37)
PY

check_loopback_listener() {
  local port=$1
  local listeners
  listeners=$(ss -lntH | awk -v suffix=":$port" '$4 ~ (suffix "$") {print $4}')
  [ -n "$listeners" ] || fail "port-$port-not-listening" 38
  while IFS= read -r address; do
    case "$address" in
      127.0.0.1:"$port"|'[::1]':"$port"|'[::ffff:127.0.0.1]':"$port") ;;
      *) fail "port-$port-not-loopback-only" 39 ;;
    esac
  done <<< "$listeners"
  echo "LOOPBACK_PORT_${port}=ok"
}

if [ "$phase" = search ] || [ "$phase" = verify ]; then
  check_loopback_listener 19200
  search_payload=$(curl --noproxy '*' -fsS --max-time 5 http://127.0.0.1:19200/)
  SEARCH_PAYLOAD="$search_payload" python3 - <<'PY'
import json
import os
import sys

payload = json.loads(os.environ["SEARCH_PAYLOAD"])
version = payload.get("version", {}).get("number", "")
print(f"OPENSEARCH_VERSION={version}")
if version != "3.4.0":
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=opensearch-version-is-not-3.4.0")
    sys.exit(40)
PY
  search_health=$(curl --noproxy '*' -fsS --max-time 5 http://127.0.0.1:19200/_cluster/health)
  SEARCH_HEALTH="$search_health" python3 - <<'PY'
import json
import os
import sys

status = json.loads(os.environ["SEARCH_HEALTH"]).get("status", "unknown")
print(f"OPENSEARCH_HEALTH={status}")
if status not in {"green", "yellow"}:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=opensearch-health-not-green-or-yellow")
    sys.exit(41)
PY
fi

if [ "$phase" = verify ]; then
  check_loopback_listener 18585
  check_loopback_listener 18586
  version_payload=$(curl --noproxy '*' -fsS --max-time 10 http://127.0.0.1:18585/api/v1/system/version)
  VERSION_PAYLOAD="$version_payload" python3 - <<'PY'
import json
import os
import sys

version = json.loads(os.environ["VERSION_PAYLOAD"]).get("version", "")
print(f"OPENMETADATA_VERSION={version}")
if version != "1.13.0":
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=openmetadata-version-is-not-1.13.0")
    sys.exit(42)
PY
  curl --noproxy '*' -fsS --max-time 10 http://127.0.0.1:18586/healthcheck >/dev/null
  echo 'OPENMETADATA_ADMIN_HEALTH=ok'
  template_payload=$(curl --noproxy '*' -fsS --max-time 10 'http://127.0.0.1:19200/_index_template')
  python3 -c '
import json
import sys

count = len(json.load(sys.stdin).get("index_templates", []))
print(f"OPENSEARCH_TEMPLATE_COUNT={count}")
if count == 0:
    print("OPENMETADATA_GATE=blocked")
    print("BLOCKER=openmetadata-search-templates-absent")
    sys.exit(43)
' <<< "$template_payload"
fi

echo "OPENMETADATA_GATE=passed"
echo "GATE_PHASE=$phase"
