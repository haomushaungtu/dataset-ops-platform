#!/usr/bin/env python3
"""Run the synthetic IAM -> adapter -> OpenMetadata metadata slice on the 139 PoC host.

The script only accepts the loopback PoC endpoints. It reads the adapter client
credential from the protected runtime environment file, keeps the access token
in memory, writes secret-free 0600 evidence, and always attempts recursive
cleanup of the unique synthetic database service.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from openmetadata_synthetic_smoke import (
    EXPECTED_FIXTURE_SHA256,
    OpenMetadataClient,
    SmokeError,
    contains_entity,
    entity_fqn,
    entity_id,
    read_fixture,
    total_hits,
    wait_search_absent,
    write_evidence,
)


OM_BASE_URL = "http://127.0.0.1:18585/api"
ADAPTER_BASE_URL = "http://127.0.0.1:19110"
EXPECTED_ENV_FILE = Path(
    "/szah/dataset-foundry-poc/config/openmetadata-adapter/openmetadata-adapter.env"
)
POC_LOG_ROOT = Path("/szah/dataset-foundry-poc/logs/openmetadata-adapter")
REQUIRED_ENV_KEYS = {
    "DF_OM_ADAPTER_IAM_TOKEN_URI",
    "DF_OM_ADAPTER_IAM_CLIENT_ID",
    "DF_OM_ADAPTER_IAM_CLIENT_SECRET",
    "DF_OM_ADAPTER_IAM_SCOPE",
}
EXPECTED_EXTENSION_KEYS = {
    "platformDatasetId",
    "platformVersionId",
    "cancerTypes",
    "modalities",
    "qualitySummary",
}


class E2EError(RuntimeError):
    """A sanitized expected E2E failure."""


def read_env(path: Path) -> dict[str, str]:
    if path.resolve(strict=False) != EXPECTED_ENV_FILE:
        raise E2EError("runtime-env-path-is-not-approved")
    if not path.is_file() or path.is_symlink():
        raise E2EError("runtime-env-missing-or-symlink")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode not in {0o600, 0o640}:
        raise E2EError("runtime-env-mode-must-be-0600-or-0640")
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[key.strip()] = value
    missing = sorted(key for key in REQUIRED_ENV_KEYS if not values.get(key))
    if missing:
        raise E2EError("runtime-env-missing-required-keys=" + ",".join(missing))
    return values


def exchange_token(config: dict[str, str]) -> str:
    token_uri = config["DF_OM_ADAPTER_IAM_TOKEN_URI"]
    if token_uri != "http://10.100.165.139:19000/oauth2/token":
        raise E2EError("token-uri-is-not-approved-internal-poc")
    client_id = config["DF_OM_ADAPTER_IAM_CLIENT_ID"]
    secret = config["DF_OM_ADAPTER_IAM_CLIENT_SECRET"]
    basic = base64.b64encode(f"{client_id}:{secret}".encode()).decode()
    body = urllib.parse.urlencode(
        {
            "grant_type": "client_credentials",
            "scope": config["DF_OM_ADAPTER_IAM_SCOPE"],
        }
    ).encode()
    request = urllib.request.Request(
        token_uri,
        data=body,
        headers={
            "Authorization": "Basic " + basic,
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.loads(response.read())
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
        raise E2EError("machine-token-exchange-failed") from exc
    token = payload.get("access_token")
    if not token:
        raise E2EError("machine-token-response-missing-access-token")
    return str(token)


def adapter_request(token: str | None, payload: dict[str, Any]) -> tuple[int, Any]:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(
        ADAPTER_BASE_URL + "/api/v1/openmetadata/dataset-versions:upsert",
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            payload = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            payload = None
        return error.code, payload
    except (urllib.error.URLError, TimeoutError) as exc:
        raise E2EError("adapter-transport-failed") from exc


def search_extension(
    client: OpenMetadataClient,
    table_name: str,
    table_id: str,
    dataset_id: str,
    timeout_seconds: int,
) -> tuple[int, int]:
    deadline = time.monotonic() + timeout_seconds
    attempts = 0
    last_total = 0
    while True:
        attempts += 1
        query = urllib.parse.urlencode(
            {
                "q": table_name,
                "index": "table_search_index",
                "from": 0,
                "size": 10,
                "deleted": "false",
                "track_total_hits": "true",
            }
        )
        result = client.request("GET", f"/v1/search/query?{query}")
        last_total = total_hits(result.payload)
        hits = result.payload.get("hits", {}).get("hits", []) if isinstance(result.payload, dict) else []
        for hit in hits:
            source = hit.get("_source", {}) if isinstance(hit, dict) else {}
            extension = source.get("extension", {}) if isinstance(source, dict) else {}
            if (
                contains_entity(result.payload, table_id)
                and isinstance(extension, dict)
                and extension.get("platformDatasetId") == dataset_id
            ):
                return attempts, last_total
        if time.monotonic() >= deadline:
            raise E2EError("synchronized-extension-not-visible-in-search")
        time.sleep(2)


def run(args: argparse.Namespace) -> dict[str, Any]:
    headers, row_count, fixture_sha256 = read_fixture(args.fixture)
    if fixture_sha256 != EXPECTED_FIXTURE_SHA256:
        raise E2EError("fixture-sha256-mismatch")
    config = read_env(args.runtime_env)
    token = exchange_token(config)
    client = OpenMetadataClient(OM_BASE_URL)
    client.token = token

    run_id = time.strftime("%Y%m%d%H%M%S", time.gmtime()) + "_" + secrets.token_hex(4)
    service_name = "om_adapter_e2e_" + run_id
    table_name = "cancer_registry"
    dataset_id = str(uuid.uuid4())
    version_id = str(uuid.uuid4())
    conflicting_dataset_id = str(uuid.uuid4())
    evidence: dict[str, Any] = {
        "run_id": run_id,
        "synthetic": True,
        "fixture_sha256": fixture_sha256,
        "fixture_rows": row_count,
        "fixture_columns": len(headers),
        "result": "running",
        "cleanup_verified": False,
    }
    service_id: str | None = None
    table_id: str | None = None
    primary_error: Exception | None = None

    try:
        direct = client.request(
            "GET", "/v1/metadata/types/name/table?fields=customProperties", expected=(200,)
        )
        evidence["machine_token_openmetadata_http_status"] = direct.status

        no_token_status, _ = adapter_request(None, {})
        evidence["adapter_no_token_http_status"] = no_token_status
        if no_token_status != 401:
            raise E2EError("adapter-did-not-reject-missing-token")

        service = client.request(
            "POST",
            "/v1/services/databaseServices",
            {
                "name": service_name,
                "serviceType": "Postgres",
                "description": "Synthetic adapter E2E metadata only; no business data.",
                "connection": {
                    "config": {
                        "type": "Postgres",
                        "scheme": "postgresql+psycopg2",
                        "hostPort": "127.0.0.1:1",
                        "username": "synthetic_poc",
                        "database": "synthetic_poc",
                    }
                },
            },
            expected=(200, 201),
        )
        service_id = entity_id(service.payload, "database-service")
        service_fqn = entity_fqn(service.payload, service_name)
        evidence["service_create_http_status"] = service.status
        evidence["service_id"] = service_id

        database = client.request(
            "POST",
            "/v1/databases",
            {"name": "synthetic_registry", "service": service_fqn},
            expected=(200, 201),
        )
        database_fqn = entity_fqn(database.payload, service_fqn + ".synthetic_registry")
        evidence["database_create_http_status"] = database.status

        schema = client.request(
            "POST",
            "/v1/databaseSchemas",
            {"name": "public", "database": database_fqn},
            expected=(200, 201),
        )
        schema_fqn = entity_fqn(schema.payload, database_fqn + ".public")
        evidence["schema_create_http_status"] = schema.status

        columns = [
            {
                "name": name,
                "dataType": "DATE" if name.endswith("_date") else "STRING",
                "ordinalPosition": position,
                "description": "Synthetic PoC field.",
            }
            for position, name in enumerate(headers, start=1)
        ]
        table = client.request(
            "POST",
            "/v1/tables",
            {
                "name": table_name,
                "databaseSchema": schema_fqn,
                "tableType": "Regular",
                "columns": columns,
                "description": "Synthetic adapter E2E table.",
            },
            expected=(200, 201),
        )
        table_id = entity_id(table.payload, "table")
        table_fqn = entity_fqn(table.payload, schema_fqn + "." + table_name)
        evidence["table_create_http_status"] = table.status
        evidence["table_id"] = table_id
        evidence["table_fqn"] = table_fqn
        evidence["dataset_id"] = dataset_id
        evidence["version_id"] = version_id

        command = {
            "dataset_id": dataset_id,
            "version_id": version_id,
            "open_metadata_table_fqn": table_fqn,
            "cancer_types": ["LUNG", "BREAST", "LUNG"],
            "modalities": ["TEXT", "STRUCTURED"],
            "quality_summary": {"score": 92.5, "grade": "A", "gate_result": "PASS"},
        }
        sync_status, sync = adapter_request(token, command)
        evidence["adapter_upsert_http_status"] = sync_status
        evidence["adapter_sync_status"] = sync.get("status") if isinstance(sync, dict) else None
        evidence["adapter_error_code"] = sync.get("code") if isinstance(sync, dict) else None
        evidence["adapter_error_detail"] = sync.get("detail") if isinstance(sync, dict) else None
        if sync_status != 200 or evidence["adapter_sync_status"] != "SYNCED":
            raise E2EError("adapter-upsert-not-synced")
        if str(sync.get("external_id")) != table_id or str(sync.get("external_fqn")) != table_fqn:
            raise E2EError("adapter-response-mapping-mismatch")

        read = client.request("GET", f"/v1/tables/{table_id}?fields=extension")
        extension = read.payload.get("extension", {}) if isinstance(read.payload, dict) else {}
        evidence["extension_read_http_status"] = read.status
        evidence["extension_keys"] = sorted(extension.keys()) if isinstance(extension, dict) else []
        if set(evidence["extension_keys"]) != EXPECTED_EXTENSION_KEYS:
            raise E2EError("extension-does-not-match-five-properties")
        if extension.get("platformDatasetId") != dataset_id:
            raise E2EError("extension-dataset-id-mismatch")
        if extension.get("platformVersionId") != version_id:
            raise E2EError("extension-version-id-mismatch")
        try:
            actual_cancer_types = json.loads(extension.get("cancerTypes", "null"))
            actual_modalities = json.loads(extension.get("modalities", "null"))
            actual_quality_summary = json.loads(extension.get("qualitySummary", "null"))
        except (TypeError, json.JSONDecodeError) as exc:
            raise E2EError("extension-json-property-invalid") from exc
        if actual_cancer_types != ["BREAST", "LUNG"]:
            raise E2EError("extension-cancer-types-mismatch")
        if actual_modalities != ["STRUCTURED", "TEXT"]:
            raise E2EError("extension-modalities-mismatch")
        if actual_quality_summary != {"score": 92.5, "grade": "A", "gate_result": "PASS"}:
            raise E2EError("extension-quality-summary-mismatch")
        evidence["extension_values_verified"] = True

        replay_status, replay = adapter_request(token, command)
        evidence["adapter_replay_http_status"] = replay_status
        evidence["adapter_replay_external_id_stable"] = (
            isinstance(replay, dict) and str(replay.get("external_id")) == table_id
        )
        if replay_status != 200 or not evidence["adapter_replay_external_id_stable"]:
            raise E2EError("adapter-replay-not-idempotent")

        conflict = dict(command)
        conflict["dataset_id"] = conflicting_dataset_id
        conflict_status, _ = adapter_request(token, conflict)
        evidence["adapter_conflict_http_status"] = conflict_status
        if conflict_status != 409:
            raise E2EError("adapter-did-not-reject-conflicting-dataset")
        after_conflict = client.request("GET", f"/v1/tables/{table_id}?fields=extension")
        after_extension = after_conflict.payload.get("extension", {})
        evidence["conflict_preserved_original_mapping"] = (
            after_extension.get("platformDatasetId") == dataset_id
        )
        if not evidence["conflict_preserved_original_mapping"]:
            raise E2EError("conflict-overwrote-original-mapping")

        attempts, hits = search_extension(
            client, table_name, table_id, dataset_id, args.search_timeout
        )
        evidence["search_attempts"] = attempts
        evidence["search_total_hits_at_match"] = hits
        evidence["result"] = "passed-before-cleanup"
    except Exception as exc:
        primary_error = exc
        evidence["result"] = "failed"
        evidence["error"] = str(exc) if isinstance(exc, (E2EError, SmokeError)) else type(exc).__name__
    finally:
        if service_id:
            try:
                query = urllib.parse.urlencode({"hardDelete": "true", "recursive": "true"})
                deleted = client.request(
                    "DELETE",
                    f"/v1/services/databaseServices/{service_id}?{query}",
                    expected=(200, 204),
                )
                evidence["service_delete_http_status"] = deleted.status
                absent = client.request(
                    "GET", f"/v1/services/databaseServices/{service_id}?include=all", expected=(404,)
                )
                evidence["service_absent_http_status"] = absent.status
                if table_id:
                    attempts, hits = wait_search_absent(
                        client, table_name, table_id, args.search_timeout
                    )
                    evidence["cleanup_search_attempts"] = attempts
                    evidence["cleanup_search_total_hits"] = hits
                evidence["cleanup_verified"] = True
            except Exception as cleanup_exc:
                evidence["cleanup_error"] = (
                    str(cleanup_exc)
                    if isinstance(cleanup_exc, (E2EError, SmokeError))
                    else type(cleanup_exc).__name__
                )
                if primary_error is None:
                    primary_error = cleanup_exc
                    evidence["result"] = "failed"
        token = ""
        client.token = None

    if primary_error is not None or not evidence["cleanup_verified"]:
        raise E2EError(json.dumps(evidence, ensure_ascii=False, sort_keys=True))
    evidence["result"] = "passed-and-cleaned"
    return evidence


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-env", type=Path, default=EXPECTED_ENV_FILE)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--search-timeout", type=int, default=60)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.search_timeout <= 120:
        print("OPENMETADATA_ADAPTER_E2E=blocked\nBLOCKER=search-timeout-out-of-range", file=sys.stderr)
        return 2
    if args.evidence.parent.resolve(strict=False) != POC_LOG_ROOT:
        print("OPENMETADATA_ADAPTER_E2E=blocked\nBLOCKER=evidence-path-is-not-approved", file=sys.stderr)
        return 2
    if not args.evidence.name.startswith("adapter-e2e-") or args.evidence.suffix != ".json":
        print("OPENMETADATA_ADAPTER_E2E=blocked\nBLOCKER=evidence-name-is-not-scoped", file=sys.stderr)
        return 2
    if args.evidence.exists() or args.evidence.is_symlink():
        print("OPENMETADATA_ADAPTER_E2E=blocked\nBLOCKER=evidence-must-be-new", file=sys.stderr)
        return 2
    evidence: dict[str, Any]
    try:
        evidence = run(args)
        write_evidence(args.evidence, evidence)
    except E2EError as error:
        try:
            evidence = json.loads(str(error))
        except json.JSONDecodeError:
            evidence = {"result": "failed", "error": str(error), "cleanup_verified": False}
        write_evidence(args.evidence, evidence)
        print("OPENMETADATA_ADAPTER_E2E=failed", file=sys.stderr)
        print("EVIDENCE=" + str(args.evidence), file=sys.stderr)
        return 1
    digest = hashlib.sha256(args.evidence.read_bytes()).hexdigest()
    print("OPENMETADATA_ADAPTER_E2E=passed-and-cleaned")
    print("EVIDENCE=" + str(args.evidence))
    print("EVIDENCE_SHA256=" + digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
