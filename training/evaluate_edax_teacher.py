"""Evaluate Edax teacher scores and their cross-level stability."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np


def load_teacher(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError(f"{path}: missing header")
        required = {
            "sample_id",
            "leaf_phase",
            "leaf_ply",
            "black",
            "white",
            "static_score",
            "edax_depth",
            "edax_score",
        }
        missing = required - set(reader.fieldnames)
        if missing:
            raise ValueError(f"{path}: missing columns {sorted(missing)}")
        return list(reader)


def correlation(left: np.ndarray, right: np.ndarray) -> float:
    if len(left) < 2 or left.std() == 0.0 or right.std() == 0.0:
        return 0.0
    return float(np.corrcoef(left, right)[0, 1])


def score_metrics(
    rows: list[dict[str, str]],
    indices: np.ndarray,
) -> dict[str, float | int]:
    static = np.asarray(
        [int(rows[index]["static_score"]) / 100.0 for index in indices],
        dtype=np.float64,
    )
    edax = np.asarray(
        [int(rows[index]["edax_score"]) for index in indices],
        dtype=np.float64,
    )
    difference = static - edax
    return {
        "samples": len(indices),
        "static_edax_correlation": correlation(static, edax),
        "static_edax_mae": float(np.abs(difference).mean()),
        "static_edax_rmse": float(np.sqrt(np.square(difference).mean())),
        "static_edax_sign_agreement": float(
            np.mean(np.sign(static) == np.sign(edax))
        ),
        "edax_mean": float(edax.mean()),
        "edax_stddev": float(edax.std()),
    }


def stability_metrics(
    rows: list[dict[str, str]],
    reference: list[dict[str, str]],
    indices: np.ndarray,
) -> dict[str, float | int]:
    candidate = np.asarray(
        [int(rows[index]["edax_score"]) for index in indices],
        dtype=np.float64,
    )
    target = np.asarray(
        [int(reference[index]["edax_score"]) for index in indices],
        dtype=np.float64,
    )
    difference = candidate - target
    exact = np.asarray(
        [
            int(rows[index]["edax_depth"])
            >= 64
            - (
                int(rows[index]["black"], 16)
                | int(rows[index]["white"], 16)
            ).bit_count()
            and int(reference[index]["edax_depth"])
            >= 64
            - (
                int(reference[index]["black"], 16)
                | int(reference[index]["white"], 16)
            ).bit_count()
            for index in indices
        ],
        dtype=np.bool_,
    )
    result: dict[str, float | int] = {
        "samples": len(indices),
        "score_correlation": correlation(candidate, target),
        "score_mae": float(np.abs(difference).mean()),
        "score_rmse": float(np.sqrt(np.square(difference).mean())),
        "score_exact_agreement": float(np.mean(difference == 0.0)),
        "score_within_2": float(np.mean(np.abs(difference) <= 2.0)),
        "sign_agreement": float(
            np.mean(np.sign(candidate) == np.sign(target))
        ),
        "both_exact_samples": int(exact.sum()),
    }
    if exact.any():
        result["both_exact_score_agreement"] = float(
            np.mean(difference[exact] == 0.0)
        )
    return result


def validate_alignment(
    rows: list[dict[str, str]],
    reference: list[dict[str, str]],
) -> None:
    if len(rows) != len(reference):
        raise ValueError("teacher files contain different row counts")
    for index, (left, right) in enumerate(
        zip(rows, reference, strict=True)
    ):
        fields = ("sample_id", "black", "white", "player")
        if any(left[field] != right[field] for field in fields):
            raise ValueError(f"teacher rows differ at index {index}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Measure Edax teacher quality and level stability.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("teacher", type=Path)
    parser.add_argument("--reference", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    rows = load_teacher(args.teacher)
    reference = (
        load_teacher(args.reference) if args.reference is not None else None
    )
    if reference is not None:
        validate_alignment(rows, reference)

    all_indices = np.arange(len(rows), dtype=np.int64)
    result: dict[str, object] = {
        "teacher": str(args.teacher),
        "overall": score_metrics(rows, all_indices),
        "phases": {},
    }
    if args.reference is not None:
        result["reference"] = str(args.reference)
    for phase in range(4):
        indices = np.asarray(
            [
                index
                for index, row in enumerate(rows)
                if int(row["leaf_phase"]) == phase
            ],
            dtype=np.int64,
        )
        phase_result = score_metrics(rows, indices)
        if reference is not None:
            phase_result["stability"] = stability_metrics(
                rows,
                reference,
                indices,
            )
        result["phases"][str(phase)] = phase_result
    if reference is not None:
        result["stability"] = stability_metrics(
            rows,
            reference,
            all_indices,
        )

    text = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    if args.output is None:
        print(text, end="")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
        print(f"Edax teacher metrics written: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
