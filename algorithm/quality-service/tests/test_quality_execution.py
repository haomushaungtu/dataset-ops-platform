from __future__ import annotations

import json
import tempfile
import unittest
import uuid
from pathlib import Path

from quality_service.adapters.data_juicer import DataJuicerAdapter, sha256_file
from quality_service.adapters.presidio import PresidioMedicalAdapter
from quality_service.core import (
    IdempotencyConflict,
    QualityRequestError,
    QualityRunner,
    QualityTaskStore,
)


REPOSITORY = Path(__file__).resolve().parents[3]
FIXTURES = REPOSITORY / "tests" / "fixtures" / "synthetic"
TABULAR = FIXTURES / "cancer-registry.csv"
NOTES = FIXTURES / "medical-notes.jsonl"


class QualityExecutionIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.runner = QualityRunner(
            QualityTaskStore(root / "quality-tasks.db"),
            root / "workspace",
            FIXTURES,
            PresidioMedicalAdapter(NOTES),
            DataJuicerAdapter(),
        )
        self.request = {
            "quality_task_id": str(uuid.uuid4()),
            "dataset_id": str(uuid.uuid4()),
            "version_id": str(uuid.uuid4()),
            "correlation_id": str(uuid.uuid4()),
            "standard_code": "CANCER_TABULAR_V1",
            "tabular_path": str(TABULAR.resolve()),
            "tabular_sha256": sha256_file(TABULAR),
            "idempotency_key": "quality-integration-test-001",
        }

    def tearDown(self) -> None:
        self.runner.store.close()
        self.temporary.cleanup()

    def test_real_engines_scores_issues_and_idempotent_replay(self) -> None:
        source_before = TABULAR.read_bytes()
        report = self.runner.run(self.request)

        engines = {engine["engine"]: engine for engine in report["engines"]}
        data_juicer = engines["data-juicer"]
        self.assertEqual("1.5.5", data_juicer["engine_version"])
        self.assertEqual(8, data_juicer["input_records"])
        self.assertEqual(7, data_juicer["output_records"])
        self.assertEqual([["ROW-002", "ROW-006"]], data_juicer["duplicate_pairs"])
        self.assertEqual(64, len(data_juicer["module_sha256"]))
        self.assertEqual(64, len(data_juicer["operator_config_sha256"]))

        presidio = engines["presidio-analyzer"]
        self.assertEqual("2.2.364", presidio["engine_version"])
        self.assertEqual(78, presidio["cells_scanned"])
        self.assertEqual(0, presidio["finding_count"])
        self.assertEqual([3, 0, 2, 0, 2], presidio["self_test"]["counts"])
        self.assertEqual(7, presidio["self_test"]["total_findings"])

        expected_issues = {
            ("REQUIRED_VALUE_MISSING", "ROW-004", "cancer_type_code", "HIGH"),
            ("VALUE_NOT_CONFORMANT", "ROW-005", "modality", "MEDIUM"),
            ("DUPLICATE_PATIENT_TOKEN", "ROW-006", "patient_token", "MEDIUM"),
            ("DATE_ORDER_INVALID", "ROW-007", "collection_date", "MEDIUM"),
            ("REQUIRED_VALUE_MISSING", "ROW-008", "consent_scope", "HIGH"),
        }
        actual_issues = {
            (issue["issue_code"], issue["record_id"], issue["field"], issue["severity"])
            for issue in report["issues"]
        }
        self.assertEqual(expected_issues, actual_issues)
        self.assertEqual(
            {
                "completeness": 97.5,
                "uniqueness": 87.5,
                "conformance": 87.5,
                "consistency": 87.5,
                "usability": 100.0,
                "label_quality": None,
                "privacy_residual": 100.0,
                "representation": 100.0,
            },
            report["dimension_scores"],
        )
        self.assertEqual(94.38, report["overall_score"])
        self.assertEqual("B", report["grade"])
        self.assertEqual("FAIL", report["automatic_gate"])
        self.assertEqual(5, report["issue_count"])
        self.assertEqual(2, report["blocking_issue_count"])
        self.assertFalse(report["listing_eligible"])
        self.assertEqual(source_before, TABULAR.read_bytes())

        serialized = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("990000199001010010", serialized)
        self.assertNotIn("19900000001", serialized)
        replay = self.runner.run(self.request)
        self.assertEqual(report, replay)
        self.assertEqual(report["report_sha256"], replay["report_sha256"])

    def test_rejects_tampered_hash_before_execution(self) -> None:
        self.request["tabular_sha256"] = "0" * 64
        with self.assertRaisesRegex(QualityRequestError, "tabular_sha256-mismatch"):
            self.runner.run(self.request)

    def test_rejects_input_outside_configured_read_only_root(self) -> None:
        outside = Path(self.temporary.name) / "outside.csv"
        outside.write_bytes(TABULAR.read_bytes())
        self.request["tabular_path"] = str(outside.resolve())
        with self.assertRaisesRegex(QualityRequestError, "outside-input-root"):
            self.runner.run(self.request)

    def test_rejects_idempotency_key_reuse_for_different_request(self) -> None:
        self.runner.run(self.request)
        changed = dict(self.request)
        changed["version_id"] = str(uuid.uuid4())
        with self.assertRaisesRegex(IdempotencyConflict, "different-request"):
            self.runner.run(changed)


if __name__ == "__main__":
    unittest.main()
