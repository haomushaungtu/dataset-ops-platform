#!/usr/bin/env python3
"""Synthetic OpenMetadata 1.13 CRUD/search/cleanup smoke test.

This tool is intentionally pinned to the loopback PoC endpoint. Authentication
material is read from a 0600 file, exchanged for a JWT, kept in memory, and
never included in evidence output.
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import json
import os
import secrets
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

if os.name == "posix":
    import pwd


EXPECTED_BASE_URL = "http://127.0.0.1:18585/api"
EXPECTED_FIXTURE_SHA256 = "a5734cc45f82557305c39fc3bb5f3c0e87e9d5d6b5a9a65f3cd1a9dcdce831e5"
POC_CONFIG_ROOT = Path("/szah/dataset-foundry-poc/config/openmetadata")
POC_LOG_ROOT = Path("/szah/dataset-foundry-poc/logs")


class SmokeError(RuntimeError):
    """An expected, sanitized PoC validation failure."""


class SmokeRunError(SmokeError):
    """A smoke workflow failure carrying only sanitized evidence."""

    def __init__(self, evidence: dict[str, Any]) -> None:
        self.evidence = evidence
        super().__init__(json.dumps(evidence, sort_keys=True))


@dataclass(frozen=True)
class HttpResult:
    status: int
    payload: Any


class OpenMetadataClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None
        self.email: str | None = None

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        expected: tuple[int, ...] = (200,),
    ) -> HttpResult:
        data = None
        headers = {"Accept": "application/json", "User-Agent": "dataset-foundry-poc/0.1"}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.email:
            headers["X-Auth-Params-Email"] = self.email
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status_code = response.status
                body = response.read()
        except urllib.error.HTTPError as exc:
            status_code = exc.code
            try:
                body = exc.read()
            finally:
                exc.close()
        except (urllib.error.URLError, TimeoutError) as exc:
            raise SmokeError(f"transport-error method={method} path={path}") from exc
        if status_code not in expected:
            raise SmokeError(f"unexpected-http-status={status_code} method={method} path={path}")
        if not body:
            decoded: Any = None
        else:
            try:
                decoded = json.loads(body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise SmokeError(f"invalid-json method={method} path={path}") from exc
        return HttpResult(status=status_code, payload=decoded)

    def login(self, email: str, password: str) -> int:
        encoded_password = base64.b64encode(password.encode("utf-8")).decode("ascii")
        result = self.request(
            "POST",
            "/v1/users/login",
            {"email": email, "password": encoded_password},
            expected=(200,),
        )
        if not isinstance(result.payload, dict) or not result.payload.get("accessToken"):
            raise SmokeError("login-response-missing-access-token")
        self.token = str(result.payload["accessToken"])
        self.email = email
        return result.status


def read_credentials(path: Path) -> tuple[str, str]:
    if not path.is_file() or path.is_symlink():
        raise SmokeError("credentials-file-missing-or-symlink")
    if os.name == "posix":
        metadata = path.stat()
        if stat.S_IMODE(metadata.st_mode) != 0o600:
            raise SmokeError("credentials-file-mode-must-be-0600")
        owner = pwd.getpwuid(metadata.st_uid).pw_name
        if owner not in {"root", "dfmetadata"}:
            raise SmokeError("credentials-file-owner-must-be-root-or-dfmetadata")

    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise SmokeError("credentials-file-has-invalid-line")
        key, value = line.split("=", 1)
        key = key.strip()
        if key not in {"OM_POC_EMAIL", "OM_POC_PASSWORD"}:
            raise SmokeError(f"credentials-file-has-unexpected-key={key}")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[key] = value
    email = values.get("OM_POC_EMAIL", "")
    password = values.get("OM_POC_PASSWORD", "")
    if not email or not password:
        raise SmokeError("credentials-file-missing-required-value")
    return email, password


def read_fixture(path: Path) -> tuple[list[str], int, str]:
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_FIXTURE_SHA256:
        raise SmokeError("synthetic-fixture-sha256-mismatch")
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        headers = reader.fieldnames or []
        rows = list(reader)
    if not headers or not rows:
        raise SmokeError("synthetic-fixture-is-empty")
    required = {"row_id", "patient_token", "cancer_type_code", "diagnosis_date"}
    if not required.issubset(headers):
        raise SmokeError("synthetic-fixture-schema-unexpected")
    if any(not str(row.get("patient_token", "")).startswith("PT-SYN-") for row in rows):
        raise SmokeError("fixture-does-not-pass-synthetic-token-gate")
    return headers, len(rows), digest


def entity_fqn(payload: Any, fallback: str) -> str:
    if isinstance(payload, dict) and payload.get("fullyQualifiedName"):
        return str(payload["fullyQualifiedName"])
    return fallback


def entity_id(payload: Any, entity_type: str) -> str:
    if not isinstance(payload, dict) or not payload.get("id"):
        raise SmokeError(f"{entity_type}-response-missing-id")
    return str(payload["id"])


def total_hits(payload: Any) -> int:
    if not isinstance(payload, dict):
        return 0
    hits = payload.get("hits", {})
    total = hits.get("total", 0) if isinstance(hits, dict) else 0
    if isinstance(total, dict):
        total = total.get("value", 0)
    return int(total or 0)


def contains_entity(payload: Any, expected_id: str) -> bool:
    if not isinstance(payload, dict):
        return False
    outer = payload.get("hits", {})
    entries = outer.get("hits", []) if isinstance(outer, dict) else []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        source = entry.get("_source", {})
        source_id = source.get("id") if isinstance(source, dict) else None
        if str(entry.get("_id", "")) == expected_id or str(source_id or "") == expected_id:
            return True
    return False


def search_table(
    client: OpenMetadataClient, table_name: str, table_id: str, timeout_seconds: int
) -> tuple[int, int]:
    deadline = time.monotonic() + timeout_seconds
    last_total = 0
    attempts = 0
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
        if contains_entity(result.payload, table_id):
            return attempts, last_total
        if time.monotonic() >= deadline:
            raise SmokeError("synthetic-table-not-found-in-search")
        time.sleep(2)


def wait_search_absent(
    client: OpenMetadataClient, table_name: str, table_id: str, timeout_seconds: int
) -> tuple[int, int]:
    deadline = time.monotonic() + timeout_seconds
    attempts = 0
    while True:
        attempts += 1
        query = urllib.parse.urlencode(
            {
                "q": table_name,
                "index": "table_search_index",
                "from": 0,
                "size": 10,
                "deleted": "all",
                "track_total_hits": "true",
            }
        )
        result = client.request("GET", f"/v1/search/query?{query}")
        last_total = total_hits(result.payload)
        if not contains_entity(result.payload, table_id):
            return attempts, last_total
        if time.monotonic() >= deadline:
            raise SmokeError("synthetic-table-remained-in-search-after-hard-delete")
        time.sleep(2)


def run_smoke(
    client: OpenMetadataClient,
    fixture: Path,
    email: str,
    password: str,
    search_timeout: int,
) -> dict[str, Any]:
    headers, row_count, fixture_sha256 = read_fixture(fixture)
    run_id = time.strftime("%Y%m%d%H%M%S", time.gmtime()) + "_" + secrets.token_hex(4)
    service_name = f"om_poc_synthetic_{run_id}"
    database_name = "synthetic_registry"
    schema_name = "public"
    table_name = "cancer_registry"
    evidence: dict[str, Any] = {
        "run_id": run_id,
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
        evidence["login_http_status"] = client.login(email, password)
        service_payload = {
            "name": service_name,
            "serviceType": "Postgres",
            "description": "Synthetic metadata-only PoC service; not connected to business data.",
            "connection": {
                "config": {
                    "type": "Postgres",
                    "scheme": "postgresql+psycopg2",
                    "hostPort": "127.0.0.1:1",
                    "username": "synthetic_poc",
                    "database": "synthetic_poc",
                }
            },
        }
        service_result = client.request(
            "POST", "/v1/services/databaseServices", service_payload, expected=(200, 201)
        )
        service_id = entity_id(service_result.payload, "database-service")
        service_fqn = entity_fqn(service_result.payload, service_name)
        evidence["service_create_http_status"] = service_result.status
        evidence["service_id"] = service_id

        database_result = client.request(
            "POST",
            "/v1/databases",
            {
                "name": database_name,
                "service": service_fqn,
                "description": "Synthetic cancer registry metadata for isolated PoC validation.",
            },
            expected=(200, 201),
        )
        database_id = entity_id(database_result.payload, "database")
        database_fqn = entity_fqn(database_result.payload, f"{service_fqn}.{database_name}")
        evidence["database_create_http_status"] = database_result.status
        evidence["database_id"] = database_id

        schema_result = client.request(
            "POST",
            "/v1/databaseSchemas",
            {
                "name": schema_name,
                "database": database_fqn,
                "description": "Synthetic schema; contains no real patient or enterprise records.",
            },
            expected=(200, 201),
        )
        schema_id = entity_id(schema_result.payload, "database-schema")
        schema_fqn = entity_fqn(schema_result.payload, f"{database_fqn}.{schema_name}")
        evidence["schema_create_http_status"] = schema_result.status
        evidence["schema_id"] = schema_id

        columns = []
        for position, name in enumerate(headers, start=1):
            data_type = "DATE" if name.endswith("_date") else "STRING"
            columns.append(
                {
                    "name": name,
                    "dataType": data_type,
                    "ordinalPosition": position,
                    "description": "Synthetic PoC field.",
                }
            )
        table_result = client.request(
            "POST",
            "/v1/tables",
            {
                "name": table_name,
                "databaseSchema": schema_fqn,
                "tableType": "Regular",
                "columns": columns,
                "description": (
                    f"Synthetic-only metadata; fixture rows={row_count}; "
                    f"fixture sha256={fixture_sha256}."
                ),
            },
            expected=(200, 201),
        )
        table_id = entity_id(table_result.payload, "table")
        evidence["table_create_http_status"] = table_result.status
        evidence["table_id"] = table_id

        read_result = client.request("GET", f"/v1/tables/{urllib.parse.quote(table_id)}")
        if entity_id(read_result.payload, "table-read") != table_id:
            raise SmokeError("table-read-id-mismatch")
        evidence["table_read_http_status"] = read_result.status
        attempts, hits = search_table(client, table_name, table_id, search_timeout)
        evidence["search_attempts"] = attempts
        evidence["search_total_hits_at_match"] = hits
        evidence["result"] = "passed-before-cleanup"
    except Exception as exc:  # Cleanup must still run for all controlled failures.
        primary_error = exc
        evidence["result"] = "failed"
        evidence["error"] = str(exc) if isinstance(exc, SmokeError) else type(exc).__name__
    finally:
        if service_id is not None:
            try:
                query = urllib.parse.urlencode({"hardDelete": "true", "recursive": "true"})
                cleanup = client.request(
                    "DELETE",
                    f"/v1/services/databaseServices/{urllib.parse.quote(service_id)}?{query}",
                    expected=(200, 204),
                )
                evidence["service_delete_http_status"] = cleanup.status
                missing = client.request(
                    "GET",
                    f"/v1/services/databaseServices/{urllib.parse.quote(service_id)}?include=all",
                    expected=(404,),
                )
                evidence["service_absent_http_status"] = missing.status
                if table_id is not None:
                    attempts, hits = wait_search_absent(
                        client, table_name, table_id, search_timeout
                    )
                    evidence["cleanup_search_attempts"] = attempts
                    evidence["cleanup_search_total_hits"] = hits
                evidence["cleanup_verified"] = True
            except Exception as cleanup_exc:
                evidence["cleanup_verified"] = False
                evidence["cleanup_error"] = (
                    str(cleanup_exc)
                    if isinstance(cleanup_exc, SmokeError)
                    else type(cleanup_exc).__name__
                )
                if primary_error is None:
                    primary_error = cleanup_exc
                    evidence["result"] = "failed"
        client.token = None
        client.email = None

    if primary_error is not None:
        raise SmokeRunError(evidence) from primary_error
    if not evidence["cleanup_verified"]:
        raise SmokeRunError(evidence)
    evidence["result"] = "passed-and-cleaned"
    return evidence


def write_evidence(path: Path, evidence: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(temporary, flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(evidence, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
        os.replace(temporary, path)
        if os.name == "posix":
            os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def path_is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve(strict=False).relative_to(root.resolve(strict=False))
        return True
    except ValueError:
        return False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=EXPECTED_BASE_URL)
    parser.add_argument("--credentials-file", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--search-timeout", type=int, default=60)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.base_url.rstrip("/") != EXPECTED_BASE_URL:
        print("OPENMETADATA_SMOKE=blocked", file=sys.stderr)
        print("BLOCKER=base-url-is-not-isolated-loopback-poc", file=sys.stderr)
        return 2
    if not 1 <= args.search_timeout <= 120:
        print("OPENMETADATA_SMOKE=blocked", file=sys.stderr)
        print("BLOCKER=search-timeout-out-of-range", file=sys.stderr)
        return 2
    if not path_is_within(args.credentials_file, POC_CONFIG_ROOT):
        print("OPENMETADATA_SMOKE=blocked", file=sys.stderr)
        print("BLOCKER=credentials-file-outside-poc-config-root", file=sys.stderr)
        return 2
    if not path_is_within(args.evidence, POC_LOG_ROOT):
        print("OPENMETADATA_SMOKE=blocked", file=sys.stderr)
        print("BLOCKER=evidence-file-outside-poc-log-root", file=sys.stderr)
        return 2
    if not args.evidence.name.startswith("openmetadata-smoke-") or args.evidence.suffix != ".json":
        print("OPENMETADATA_SMOKE=blocked", file=sys.stderr)
        print("BLOCKER=evidence-file-name-is-not-scoped", file=sys.stderr)
        return 2
    if args.evidence.is_symlink():
        print("OPENMETADATA_SMOKE=blocked", file=sys.stderr)
        print("BLOCKER=evidence-file-must-not-be-symlink", file=sys.stderr)
        return 2
    try:
        email, password = read_credentials(args.credentials_file)
        client = OpenMetadataClient(args.base_url)
        evidence = run_smoke(client, args.fixture, email, password, args.search_timeout)
        write_evidence(args.evidence, evidence)
    except SmokeRunError as exc:
        write_evidence(args.evidence, exc.evidence)
        print("OPENMETADATA_SMOKE=failed", file=sys.stderr)
        print(str(exc), file=sys.stderr)
        print(f"EVIDENCE={args.evidence}", file=sys.stderr)
        return 1
    except SmokeError as exc:
        print("OPENMETADATA_SMOKE=failed", file=sys.stderr)
        print(str(exc), file=sys.stderr)
        return 1
    finally:
        if "password" in locals():
            password = ""
    print("OPENMETADATA_SMOKE=passed-and-cleaned")
    print(f"EVIDENCE={args.evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
