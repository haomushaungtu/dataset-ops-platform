from __future__ import annotations

import csv
import hashlib
import json
import re
import sqlite3
import uuid
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from .adapters.data_juicer import DataJuicerAdapter, sha256_file
from .adapters.presidio import PresidioMedicalAdapter


STANDARD_CODE = "CANCER_TABULAR_V1"
STANDARD_VERSION = "0.1.0"
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_COLUMNS = (
    "row_id",
    "patient_token",
    "cancer_type_code",
    "modality",
    "specimen_type",
    "age_band",
    "stage",
    "diagnosis_date",
    "collection_date",
    "consent_scope",
)
WEIGHTS = {
    "completeness": 18,
    "uniqueness": 10,
    "conformance": 12,
    "consistency": 14,
    "usability": 12,
    "label_quality": 12,
    "privacy_residual": 12,
    "representation": 10,
}


class QualityRequestError(ValueError):
    pass


class IdempotencyConflict(RuntimeError):
    pass


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _score(total: int, failures: int) -> float:
    if total <= 0:
        return 100.0
    return round(max(0.0, 100.0 * (total - failures) / total), 2)


def _issue(
    code: str,
    severity: str,
    record_id: str,
    field: str,
    engine: str,
    message: str,
) -> dict[str, str]:
    return {
        "issue_code": code,
        "severity": severity,
        "record_id": record_id,
        "field": field,
        "engine": engine,
        "message": message,
        "status": "OPEN",
    }


class QualityTaskStore:
    def __init__(self, database: Path) -> None:
        database.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(database)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS quality_execution (
                execution_id TEXT PRIMARY KEY,
                idempotency_key TEXT NOT NULL UNIQUE,
                request_sha256 TEXT NOT NULL,
                execution_identity TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                result_json TEXT,
                error_code TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()

    def claim(
        self, idempotency_key: str, request_sha256: str, execution_identity: str
    ) -> tuple[str, dict[str, Any] | None]:
        row = self.connection.execute(
            "SELECT * FROM quality_execution WHERE idempotency_key = ?", (idempotency_key,)
        ).fetchone()
        if row:
            if row["request_sha256"] != request_sha256:
                raise IdempotencyConflict("idempotency-key-reused-with-different-request")
            if row["status"] == "SUCCEEDED" and row["result_json"]:
                return str(row["execution_id"]), json.loads(row["result_json"])
            raise IdempotencyConflict("idempotent-execution-not-replayable-in-current-state")
        identity_row = self.connection.execute(
            "SELECT * FROM quality_execution WHERE execution_identity = ?", (execution_identity,)
        ).fetchone()
        if identity_row:
            if identity_row["request_sha256"] != request_sha256:
                raise IdempotencyConflict("execution-identity-conflicts-with-existing-request")
            if identity_row["status"] == "SUCCEEDED" and identity_row["result_json"]:
                return str(identity_row["execution_id"]), json.loads(identity_row["result_json"])
            raise IdempotencyConflict("execution-identity-not-replayable-in-current-state")
        execution_id = str(uuid.uuid4())
        now = _utc_now()
        self.connection.execute(
            """
            INSERT INTO quality_execution (
                execution_id, idempotency_key, request_sha256, execution_identity,
                status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'RUNNING', ?, ?)
            """,
            (execution_id, idempotency_key, request_sha256, execution_identity, now, now),
        )
        self.connection.commit()
        return execution_id, None

    def succeed(self, execution_id: str, result: Mapping[str, Any]) -> None:
        self.connection.execute(
            """
            UPDATE quality_execution
               SET status = 'SUCCEEDED', result_json = ?, error_code = NULL, updated_at = ?
             WHERE execution_id = ? AND status = 'RUNNING'
            """,
            (json.dumps(result, ensure_ascii=False, sort_keys=True), _utc_now(), execution_id),
        )
        self.connection.commit()

    def fail(self, execution_id: str, error_code: str) -> None:
        self.connection.execute(
            """
            UPDATE quality_execution
               SET status = 'FAILED', error_code = ?, updated_at = ?
             WHERE execution_id = ? AND status = 'RUNNING'
            """,
            (error_code, _utc_now(), execution_id),
        )
        self.connection.commit()


class QualityRunner:
    def __init__(
        self,
        store: QualityTaskStore,
        workspace: Path,
        input_root: Path,
        presidio: PresidioMedicalAdapter,
        data_juicer: DataJuicerAdapter | None = None,
    ) -> None:
        self.store = store
        self.workspace = workspace.resolve()
        self.input_root = input_root.resolve(strict=True)
        if not self.input_root.is_dir():
            raise QualityRequestError("input-root-must-be-directory")
        self.data_juicer = data_juicer or DataJuicerAdapter()
        self.presidio = presidio

    def run(self, request: Mapping[str, Any]) -> dict[str, Any]:
        validated = self._validate_request(request)
        canonical = json.dumps(validated["canonical_request"], sort_keys=True, separators=(",", ":"))
        request_sha256 = hashlib.sha256(canonical.encode()).hexdigest()
        execution_identity = hashlib.sha256(
            (
                validated["quality_task_id"]
                + ":"
                + STANDARD_CODE
                + ":"
                + validated["tabular_sha256"]
            ).encode()
        ).hexdigest()
        execution_id, replay = self.store.claim(
            validated["idempotency_key"], request_sha256, execution_identity
        )
        if replay is not None:
            return replay
        try:
            result = self._execute(execution_id, request_sha256, validated)
            self.store.succeed(execution_id, result)
            return result
        except Exception as exc:
            self.store.fail(execution_id, type(exc).__name__)
            raise

    def _validate_request(self, request: Mapping[str, Any]) -> dict[str, Any]:
        required = {
            "quality_task_id",
            "dataset_id",
            "version_id",
            "correlation_id",
            "standard_code",
            "tabular_path",
            "tabular_sha256",
            "idempotency_key",
        }
        missing = sorted(key for key in required if not request.get(key))
        if missing:
            raise QualityRequestError("missing-fields=" + ",".join(missing))
        for key in ("quality_task_id", "dataset_id", "version_id", "correlation_id"):
            try:
                uuid.UUID(str(request[key]))
            except ValueError as exc:
                raise QualityRequestError(f"{key}-must-be-uuid") from exc
        if request["standard_code"] != STANDARD_CODE:
            raise QualityRequestError("unsupported-standard-code")
        idempotency_key = str(request["idempotency_key"])
        if not 8 <= len(idempotency_key) <= 128:
            raise QualityRequestError("invalid-idempotency-key-length")

        paths: dict[str, Path] = {}
        for path_key, hash_key in (("tabular_path", "tabular_sha256"),):
            raw_path = Path(str(request[path_key]))
            if not raw_path.is_absolute():
                raise QualityRequestError(f"{path_key}-must-be-absolute")
            if raw_path.is_symlink() or not raw_path.is_file():
                raise QualityRequestError(f"{path_key}-must-be-regular-file")
            expected = str(request[hash_key]).lower()
            if not HEX_64.fullmatch(expected):
                raise QualityRequestError(f"{hash_key}-must-be-sha256")
            resolved = raw_path.resolve()
            if not resolved.is_relative_to(self.input_root):
                raise QualityRequestError(f"{path_key}-outside-input-root")
            if sha256_file(resolved) != expected:
                raise QualityRequestError(f"{hash_key}-mismatch")
            paths[path_key] = resolved

        canonical_request = {
            "quality_task_id": str(request["quality_task_id"]),
            "dataset_id": str(request["dataset_id"]),
            "version_id": str(request["version_id"]),
            "correlation_id": str(request["correlation_id"]),
            "standard_code": STANDARD_CODE,
            "tabular_sha256": str(request["tabular_sha256"]).lower(),
        }
        return {
            "quality_task_id": canonical_request["quality_task_id"],
            "dataset_id": canonical_request["dataset_id"],
            "version_id": canonical_request["version_id"],
            "correlation_id": canonical_request["correlation_id"],
            "idempotency_key": idempotency_key,
            "tabular_path": paths["tabular_path"],
            "tabular_sha256": canonical_request["tabular_sha256"],
            "canonical_request": {
                key: value for key, value in canonical_request.items() if key != "correlation_id"
            },
        }

    def _execute(
        self, execution_id: str, request_sha256: str, validated: Mapping[str, Any]
    ) -> dict[str, Any]:
        started_at = _utc_now()
        rows = self._read_tabular(validated["tabular_path"])
        issues: list[dict[str, Any]] = []

        source_hashes = {validated["tabular_path"]: validated["tabular_sha256"]}
        data_juicer_result = self.data_juicer.execute(
            rows, self.workspace / execution_id / "data-juicer", source_hashes
        )
        metrics = self._tabular_metrics(
            rows, issues, list(data_juicer_result["duplicate_pairs"])
        )
        presidio_result = self.presidio.analyze_cells(rows, validated["correlation_id"])
        privacy_findings = list(presidio_result.pop("findings"))
        for finding in privacy_findings:
            issues.append(
                {
                    "issue_code": "PRIVACY_" + str(finding["entity_type"]),
                    "severity": finding["severity"],
                    "record_id": finding["record_id"],
                    "field": finding["field"],
                    "engine": finding["engine"],
                    "message": "检测到需人工复核的医疗敏感信息风险",
                    "status": "OPEN",
                    "start": finding["start"],
                    "end": finding["end"],
                    "confidence": finding["confidence"],
                    "value_sha256": finding["value_sha256"],
                }
            )
        high_privacy = sum(1 for finding in privacy_findings if finding["severity"] == "HIGH")
        medium_privacy = len(privacy_findings) - high_privacy
        metrics["privacy_residual"] = max(0.0, round(100.0 - 15 * high_privacy - 5 * medium_privacy, 2))

        for source_path, expected_hash in source_hashes.items():
            if sha256_file(source_path) != expected_hash:
                raise QualityRequestError("source-mutated-during-quality-run")
        applicable_metrics = {name: score for name, score in metrics.items() if score is not None}
        overall = round(
            sum(applicable_metrics[name] * WEIGHTS[name] for name in applicable_metrics)
            / sum(WEIGHTS[name] for name in applicable_metrics),
            2,
        )
        grade = "A" if overall >= 95 else "B" if overall >= 90 else "C" if overall >= 85 else "D" if overall >= 70 else "E"
        blocking = [issue for issue in issues if issue["severity"] in {"CRITICAL", "HIGH"}]
        automatic_gate = (
            "PASS_PENDING_HUMAN_REVIEW"
            if overall >= 85
            and min(applicable_metrics.values()) >= 70
            and metrics["privacy_residual"] >= 90
            and not blocking
            else "FAIL"
        )
        for issue in issues:
            stable = ":".join(
                [
                    validated["quality_task_id"],
                    validated["tabular_sha256"],
                    str(issue["issue_code"]),
                    str(issue["record_id"]),
                    str(issue["field"]),
                ]
            )
            issue["issue_id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, stable))
        report = {
            "execution_id": execution_id,
            "status": "SUCCEEDED",
            "quality_task_id": validated["quality_task_id"],
            "dataset_id": validated["dataset_id"],
            "version_id": validated["version_id"],
            "standard_code": STANDARD_CODE,
            "standard_version": STANDARD_VERSION,
            "request_sha256": request_sha256,
            "input_manifest": {
                "tabular": {
                    "filename": validated["tabular_path"].name,
                    "sha256": validated["tabular_sha256"],
                    "rows": len(rows),
                },
                "read_only_verified": True,
            },
            "engines": [data_juicer_result, presidio_result],
            "dimension_scores": metrics,
            "weights": WEIGHTS,
            "excluded_dimensions": ["label_quality"],
            "overall_score": overall,
            "grade": grade,
            "automatic_gate": automatic_gate,
            "human_review_required": True,
            "listing_eligible": False,
            "issue_count": len(issues),
            "blocking_issue_count": len(blocking),
            "issues": issues,
            "started_at": started_at,
            "completed_at": _utc_now(),
        }
        report["report_sha256"] = hashlib.sha256(
            json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        return report

    @staticmethod
    def _read_tabular(path: Path) -> list[dict[str, str]]:
        with path.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            if tuple(reader.fieldnames or ()) != REQUIRED_COLUMNS:
                raise QualityRequestError("tabular-schema-does-not-match-standard")
            rows = [{key: (value or "").strip() for key, value in row.items()} for row in reader]
        if not rows:
            raise QualityRequestError("tabular-input-is-empty")
        return rows

    @staticmethod
    def _tabular_metrics(
        rows: list[dict[str, str]],
        issues: list[dict[str, Any]],
        duplicate_pairs: list[list[str]],
    ) -> dict[str, float | None]:
        required_value_fields = list(REQUIRED_COLUMNS)
        completeness_failures = 0
        for row in rows:
            for field in required_value_fields:
                if not row[field]:
                    completeness_failures += 1
                    issues.append(
                        _issue("REQUIRED_VALUE_MISSING", "HIGH", row["row_id"], field, "cancer-rules", "必填字段缺失")
                    )
        completeness = _score(len(rows) * len(required_value_fields), completeness_failures)

        duplicate_count = sum(max(0, len(pair) - 1) for pair in duplicate_pairs)
        for pair in duplicate_pairs:
            for duplicate in pair[1:]:
                issue = _issue("DUPLICATE_PATIENT_TOKEN", "MEDIUM", duplicate, "patient_token", "data-juicer", "存在重复患者伪标识")
                issue["related_record_id"] = pair[0]
                issues.append(issue)

        conformance_failures = 0
        consistency_checks = 0
        consistency_failures = 0
        modalities = {"STRUCTURED", "TEXT", "IMAGE"}
        for row in rows:
            row_conformant = True
            if row["modality"] not in modalities:
                row_conformant = False
                issues.append(_issue("VALUE_NOT_CONFORMANT", "MEDIUM", row["row_id"], "modality", "cancer-rules", "字段值不符合固定标准"))
            parsed_dates: dict[str, date] = {}
            for field in ("diagnosis_date", "collection_date"):
                try:
                    parsed_dates[field] = date.fromisoformat(row[field])
                except ValueError:
                    row_conformant = False
                    issues.append(_issue("DATE_INVALID", "MEDIUM", row["row_id"], field, "cancer-rules", "日期格式无效"))
            if not row_conformant:
                conformance_failures += 1
            if len(parsed_dates) == 2:
                consistency_checks += 1
                if parsed_dates["collection_date"] > parsed_dates["diagnosis_date"]:
                    consistency_failures += 1
                    issues.append(_issue("DATE_ORDER_INVALID", "MEDIUM", row["row_id"], "collection_date", "cancer-rules", "采集日期晚于诊断日期"))
        distribution = Counter(row["cancer_type_code"] or "MISSING" for row in rows)
        concentration = max(distribution.values()) / len(rows)
        representation = round(max(0.0, 100.0 - max(0.0, concentration - 0.5) * 100.0), 2)
        return {
            "completeness": completeness,
            "uniqueness": _score(len(rows), duplicate_count),
            "conformance": _score(len(rows), conformance_failures),
            "consistency": _score(consistency_checks, consistency_failures),
            "usability": 100.0,
            "label_quality": None,
            "privacy_residual": 0.0,
            "representation": representation,
        }
