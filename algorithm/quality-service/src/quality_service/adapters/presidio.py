from __future__ import annotations

import hashlib
import inspect
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping

from presidio_analyzer import AnalyzerEngine, Pattern, PatternRecognizer, RecognizerRegistry
from presidio_analyzer.nlp_engine import NoOpNlpEngine


PRESIDIO_COMMIT = "779dbd286d5ef4d1fbe2514275fb1bce358f2417"
PRESIDIO_VERSION = "2.2.364"
ANALYZER_ENGINE_SHA256 = "28a1ad308244634764769009c6618332c4bf7c6ea1078471d90f66653db6d0ca"
SELF_TEST_SHA256 = "918f98f3e0bb5892c109d039439f1d1ff0a900ec5b5db421bf07dfaa3a649473"


@dataclass(frozen=True)
class PrivacyFinding:
    record_id: str
    field: str
    entity_type: str
    start: int
    end: int
    score: float
    severity: str
    value_sha256: str

    def as_dict(self) -> dict[str, object]:
        return {
            "record_id": self.record_id,
            "field": self.field,
            "entity_type": self.entity_type,
            "start": self.start,
            "end": self.end,
            "confidence": round(self.score, 4),
            "severity": self.severity,
            "value_sha256": self.value_sha256,
            "engine": "presidio-analyzer",
        }


def _recognizers() -> list[PatternRecognizer]:
    specifications = [
        ("CN_ID_CARD", "cn_id_card", r"(?<!\d)\d{17}[\dXx](?!\d)", 0.90),
        ("CN_MOBILE", "cn_mobile", r"(?<!\d)1[3-9]\d{9}(?!\d)", 0.90),
        ("MEDICAL_RECORD_ID", "medical_record_id", r"(?:病历号|病案号)\s*[:：]?\s*[A-Za-z0-9-]{4,32}", 0.85),
        ("OUTPATIENT_ID", "outpatient_id", r"门诊号\s*[:：]?\s*[A-Za-z0-9-]{4,32}", 0.85),
        ("INPATIENT_ID", "inpatient_id", r"住院号\s*[:：]?\s*[A-Za-z0-9-]{4,32}", 0.85),
        ("CLINICIAN_NAME", "clinician_name", r"(?:医生|复核人员为)\s*[\u4e00-\u9fff]{2,8}", 0.75),
    ]
    return [
        PatternRecognizer(
            supported_entity=entity,
            patterns=[Pattern(name, expression, score)],
            supported_language="zh",
        )
        for entity, name, expression, score in specifications
    ]


def _recognizer_config_sha256() -> str:
    payload = [recognizer.to_dict() for recognizer in _recognizers()]
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


class PresidioMedicalAdapter:
    def __init__(self, self_test_path: Path) -> None:
        module_path = Path(inspect.getfile(AnalyzerEngine)).resolve()
        self.module_sha256 = hashlib.sha256(module_path.read_bytes()).hexdigest()
        if self.module_sha256 != ANALYZER_ENGINE_SHA256:
            raise RuntimeError("presidio-source-hash-mismatch")
        if self_test_path.is_symlink() or not self_test_path.is_file():
            raise RuntimeError("presidio-self-test-file-invalid")
        if hashlib.sha256(self_test_path.read_bytes()).hexdigest() != SELF_TEST_SHA256:
            raise RuntimeError("presidio-self-test-hash-mismatch")
        registry = RecognizerRegistry(supported_languages=["zh"])
        for recognizer in _recognizers():
            registry.add_recognizer(recognizer)
        self.entities = sorted({entity for recognizer in registry.recognizers for entity in recognizer.supported_entities})
        self.engine = AnalyzerEngine(
            registry=registry,
            nlp_engine=NoOpNlpEngine(models=[{"lang_code": "zh", "model_name": "no_op"}]),
            supported_languages=["zh"],
        )
        self.self_test = self._run_self_test(self_test_path)

    def _scan(self, record_id: str, field: str, text: str, correlation_id: str) -> list[PrivacyFinding]:
        findings: list[PrivacyFinding] = []
        results = self.engine.analyze(
            text=text,
            language="zh",
            entities=self.entities,
            score_threshold=0.7,
            correlation_id=correlation_id,
        )
        for result in results:
            matched = text[result.start : result.end]
            findings.append(
                PrivacyFinding(
                    record_id=record_id,
                    field=field,
                    entity_type=result.entity_type,
                    start=result.start,
                    end=result.end,
                    score=result.score,
                    severity="HIGH" if result.score >= 0.85 else "MEDIUM",
                    value_sha256=hashlib.sha256(matched.encode()).hexdigest(),
                )
            )
        return findings

    def _run_self_test(self, path: Path) -> dict[str, object]:
        counts: list[int] = []
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if not line.strip():
                    continue
                note = json.loads(line)
                findings = self._scan(str(note["record_id"]), "text", str(note["text"]), "presidio-readiness")
                actual = sorted(finding.entity_type for finding in findings)
                expected = sorted(str(entity) for entity in note["expected_entities"])
                if actual != expected:
                    raise RuntimeError("presidio-self-test-entity-mismatch")
                counts.append(len(actual))
        if counts != [3, 0, 2, 0, 2]:
            raise RuntimeError("presidio-self-test-count-mismatch")
        return {"fixture_sha256": SELF_TEST_SHA256, "counts": counts, "total_findings": sum(counts), "passed": True}

    def analyze_cells(self, rows: Iterable[Mapping[str, str]], correlation_id: str) -> dict[str, object]:
        findings: list[PrivacyFinding] = []
        scanned_cells = 0
        for row in rows:
            for field, value in row.items():
                if value:
                    scanned_cells += 1
                    findings.extend(self._scan(str(row.get("row_id", "")), field, str(value), correlation_id))
        return {
            "engine": "presidio-analyzer",
            "engine_version": PRESIDIO_VERSION,
            "source_commit": PRESIDIO_COMMIT,
            "class_fqn": "presidio_analyzer.analyzer_engine.AnalyzerEngine",
            "module_sha256": self.module_sha256,
            "recognizer_config_sha256": _recognizer_config_sha256(),
            "recognizer_mode": "analyzer-engine-no-op-nlp-pattern-recognizers",
            "cells_scanned": scanned_cells,
            "finding_count": len(findings),
            "self_test": self.self_test,
            "findings": [finding.as_dict() for finding in findings],
        }
