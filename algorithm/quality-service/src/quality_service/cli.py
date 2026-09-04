from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .adapters.data_juicer import DataJuicerAdapter
from .adapters.presidio import PresidioMedicalAdapter
from .core import IdempotencyConflict, QualityRequestError, QualityRunner, QualityTaskStore


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run a fixed, read-only phase-one quality execution.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    run = subparsers.add_parser("run")
    run.add_argument("--request", type=Path, required=True)
    run.add_argument("--database", type=Path, required=True)
    run.add_argument("--workspace", type=Path, required=True)
    run.add_argument("--input-root", type=Path, required=True)
    run.add_argument("--presidio-self-test-file", type=Path, required=True)
    run.add_argument("--result", type=Path)
    return parser


def main() -> int:
    args = _parser().parse_args()
    if args.command != "run":
        return 2
    if not args.request.is_file() or args.request.is_symlink():
        print("QUALITY_EXECUTION=blocked\nBLOCKER=request-must-be-regular-file", file=sys.stderr)
        return 2
    try:
        request = json.loads(args.request.read_text(encoding="utf-8"))
        runner = QualityRunner(
            QualityTaskStore(args.database),
            args.workspace,
            args.input_root,
            PresidioMedicalAdapter(args.presidio_self_test_file),
            DataJuicerAdapter(),
        )
        result = runner.run(request)
    except (QualityRequestError, IdempotencyConflict) as exc:
        print(f"QUALITY_EXECUTION=blocked\nBLOCKER={exc}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"QUALITY_EXECUTION=failed\nERROR={type(exc).__name__}", file=sys.stderr)
        return 1
    serialized = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.result:
        if args.result.exists() or args.result.is_symlink():
            print("QUALITY_EXECUTION=blocked\nBLOCKER=result-must-be-new", file=sys.stderr)
            return 2
        args.result.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(serialized)
    else:
        print(serialized, end="")
    print("QUALITY_EXECUTION=passed", file=sys.stderr)
    print("EXECUTION_ID=" + str(result["execution_id"]), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
