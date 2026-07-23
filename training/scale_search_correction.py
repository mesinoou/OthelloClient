"""Scale a learned correction and merge it with its runtime base model."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from training.java_model import merge_java_models, scale_java_model


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a reduced-strength search-correction candidate.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--correction-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--scale", type=float, required=True)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.output_dir.exists() and not args.overwrite:
        raise FileExistsError(
            f"output directory exists; pass --overwrite: {args.output_dir}"
        )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    scaled_path = args.output_dir / "correction-tables.bin"
    candidate_path = args.output_dir / "evaluation-tables.bin"
    scale_result = scale_java_model(
        args.correction_model,
        scaled_path,
        args.scale,
    )
    merge_result = merge_java_models(
        args.base_model,
        scaled_path,
        candidate_path,
    )
    metadata = {
        "base_model": str(args.base_model),
        "correction_model": str(args.correction_model),
        "scale": args.scale,
        "scaled_correction": scale_result,
        "candidate": merge_result,
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"scaled search-correction model written: {candidate_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
