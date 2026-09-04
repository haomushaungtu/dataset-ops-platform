from __future__ import annotations

import hashlib
import inspect
import json
from pathlib import Path
from typing import Iterable, Mapping

from data_juicer.core.data import NestedDataset
from data_juicer.ops.deduplicator.document_deduplicator import DocumentDeduplicator
from data_juicer import __version__ as data_juicer_version


DATA_JUICER_COMMIT = "0e40a8659a759286d9bb3899cb3ef7f6fdbc624c"
DOCUMENT_DEDUPLICATOR_SHA256 = "bc3651612cbb24d4e81924e6e9e871601d8a23b7c1f2e96a92bb7b82a825598b"


class DataJuicerExecutionError(RuntimeError):
    """Raised when the fixed Data-Juicer operation cannot be proven successful."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _configuration_sha256() -> str:
    configuration = {
        "operator": "document_deduplicator",
        "text_key": "patient_token",
        "lowercase": False,
        "ignore_non_character": False,
        "skip_op_error": False,
        "num_proc": 1,
    }
    encoded = json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


class DataJuicerAdapter:
    def execute(
        self,
        records: Iterable[Mapping[str, str]],
        work_dir: Path,
        source_hashes: Mapping[Path, str],
    ) -> dict[str, object]:
        work_dir.mkdir(parents=True, exist_ok=False)
        materialized = [dict(record) for record in records]
        if not materialized:
            raise DataJuicerExecutionError("data-juicer-input-is-empty")
        module_path = Path(inspect.getfile(DocumentDeduplicator)).resolve()
        module_sha256 = sha256_file(module_path)
        if module_sha256 != DOCUMENT_DEDUPLICATOR_SHA256:
            raise DataJuicerExecutionError("data-juicer-source-hash-mismatch")

        dataset = NestedDataset.from_list(materialized)
        operator = DocumentDeduplicator(
            text_key="patient_token",
            lowercase=False,
            ignore_non_character=False,
            skip_op_error=False,
        )
        dataset = dataset.map(operator.compute_hash, num_proc=1, load_from_cache_file=False)
        output, duplicate_pairs = operator.process(dataset, show_num=len(materialized))
        normalized_pairs = sorted(
            sorted(str(sample["row_id"]) for sample in pair)
            for pair in duplicate_pairs.values()
        )
        input_manifest_sha256 = hashlib.sha256(
            json.dumps(materialized, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        output_rows = output.to_list()
        output_manifest_sha256 = hashlib.sha256(
            json.dumps(output_rows, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        for source_path, expected_hash in source_hashes.items():
            if sha256_file(source_path) != expected_hash:
                raise DataJuicerExecutionError("source-mutated-during-data-juicer-run")
        return {
            "engine": "data-juicer",
            "engine_version": data_juicer_version,
            "source_commit": DATA_JUICER_COMMIT,
            "class_fqn": "data_juicer.ops.deduplicator.document_deduplicator.DocumentDeduplicator",
            "module_sha256": module_sha256,
            "operator_config_sha256": _configuration_sha256(),
            "operator": "document_deduplicator",
            "input_records": len(materialized),
            "output_records": len(output),
            "duplicate_pairs": normalized_pairs,
            "input_manifest_sha256": input_manifest_sha256,
            "output_manifest_sha256": output_manifest_sha256,
            "network_mode": "offline-no-dynamic-install",
        }
