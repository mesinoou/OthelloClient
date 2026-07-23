"""Add phase-specific side-to-move biases to a Java evaluation model."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from training.java_model import adjust_java_model_bias, read_java_model


def parse_values(value: str) -> tuple[float, ...]:
    values = tuple(float(item) for item in value.split(","))
    if len(values) != 4:
        raise ValueError("phase biases must contain four values")
    if not all(np.isfinite(item) for item in values):
        raise ValueError("phase biases must be finite")
    return values


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Add phase-specific side-to-move model biases.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--bias-discs",
        required=True,
        help="comma-separated phase additions in disc units",
    )
    parser.add_argument("--scale", type=float, default=1.0)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 0.0 <= args.scale <= 1.0:
        raise ValueError("scale must be between zero and one")
    if args.output_dir.exists() and not args.overwrite:
        raise FileExistsError(
            f"output directory exists; pass --overwrite: {args.output_dir}"
        )
    args.output_dir.mkdir(parents=True, exist_ok=True)

    base = read_java_model(args.base_model)
    bias_discs = parse_values(args.bias_discs)
    additions = tuple(
        int(
            np.rint(
                value
                * args.scale
                * base.score_scale
                * base.score_divisor
                / 64.0
            )
        )
        for value in bias_discs
    )
    output_path = args.output_dir / "evaluation-tables.bin"
    export = adjust_java_model_bias(
        args.base_model,
        output_path,
        additions,
    )
    metadata = {
        "base_model": str(args.base_model),
        "bias_discs": bias_discs,
        "scale": args.scale,
        "score_additions": additions,
        "base_phase_bias": base.phase_bias,
        "candidate_phase_bias": tuple(
            bias + addition
            for bias, addition in zip(
                base.phase_bias,
                additions,
                strict=True,
            )
        ),
        "candidate": export,
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"bias-adjusted model written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
