"""Create a reproducible table-wise interpolation of two Java models."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil

from training.java_model import interpolate_java_models


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Interpolate compatible Java evaluation models.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--candidate-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--alpha", type=float, required=True)
    parser.add_argument(
        "--phase-scales",
        default="1,1,1,1",
        help="comma-separated phase multipliers applied after alpha",
    )
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_phase_scales(value: str) -> tuple[float, ...]:
    scales = tuple(float(item) for item in value.split(","))
    if len(scales) != 4:
        raise ValueError("phase scales must contain four values")
    if any(not 0.0 <= scale <= 1.0 for scale in scales):
        raise ValueError("phase scales must be between 0 and 1")
    return scales


def main() -> int:
    args = parse_args()
    phase_scales = parse_phase_scales(args.phase_scales)
    if args.output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {args.output_dir}"
            )
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)

    output_path = args.output_dir / "evaluation-tables.bin"
    export = interpolate_java_models(
        args.base_model,
        args.candidate_model,
        output_path,
        args.alpha,
        phase_scales,
    )
    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "base_model": {
            "path": str(args.base_model),
            "sha256": sha256_file(args.base_model),
        },
        "candidate_model": {
            "path": str(args.candidate_model),
            "sha256": sha256_file(args.candidate_model),
        },
        "alpha": args.alpha,
        "phase_scales": phase_scales,
        "output": {
            "path": str(output_path),
            "sha256": sha256_file(output_path),
            **export,
        },
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"blended Java model written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
