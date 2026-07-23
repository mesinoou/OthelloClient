"""Fit an antisymmetric potential-mobility residual table."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np

from training.java_model import read_java_model


PHASE_COUNT = 4
MOBILITY_STATES = 65
REQUIRED = {
    "phase",
    "static_score",
    "edax_score",
    "occurrences",
    "potential_mobility_own",
    "potential_mobility_opponent",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_split(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        missing = REQUIRED - set(archive.files)
        if missing:
            raise ValueError(f"{path}: missing arrays {sorted(missing)}")
        return {name: archive[name] for name in REQUIRED}


def sample_weights(data: dict[str, np.ndarray]) -> np.ndarray:
    values = np.sqrt(data["occurrences"].astype(np.float64))
    return np.minimum(values, 8.0)


def residual_scores(
    data: dict[str, np.ndarray],
    score_scale: int,
) -> np.ndarray:
    teacher = (
        data["edax_score"].astype(np.float64) * score_scale / 64.0
    )
    return teacher - data["static_score"].astype(np.float64)


def fit_phase_table(
    data: dict[str, np.ndarray],
    phase: int,
    score_scale: int,
    ridge: float,
) -> np.ndarray:
    indices = np.flatnonzero(data["phase"] == phase)
    own = data["potential_mobility_own"][indices].astype(np.int16)
    opponent = data["potential_mobility_opponent"][indices].astype(np.int16)
    targets = residual_scores(data, score_scale)[indices]
    weights = sample_weights(data)[indices]

    numerator = np.zeros((MOBILITY_STATES, MOBILITY_STATES))
    denominator = np.zeros((MOBILITY_STATES, MOBILITY_STATES))
    for first, second, target, weight in zip(
        own,
        opponent,
        targets,
        weights,
        strict=True,
    ):
        low = min(int(first), int(second))
        high = max(int(first), int(second))
        if low == high:
            continue
        sign = 1.0 if first == low else -1.0
        numerator[low, high] += weight * sign * target
        denominator[low, high] += weight

    table = np.zeros((MOBILITY_STATES, MOBILITY_STATES))
    for low in range(MOBILITY_STATES):
        for high in range(low + 1, MOBILITY_STATES):
            if denominator[low, high] == 0.0:
                continue
            value = numerator[low, high] / (
                denominator[low, high] + ridge
            )
            table[low, high] = value
            table[high, low] = -value
    return table


def metrics(
    data: dict[str, np.ndarray],
    phase: int,
    score_scale: int,
    table: np.ndarray,
) -> dict[str, float | int]:
    indices = np.flatnonzero(data["phase"] == phase)
    own = data["potential_mobility_own"][indices]
    opponent = data["potential_mobility_opponent"][indices]
    target = residual_scores(data, score_scale)[indices]
    correction = table[own, opponent]
    weights = sample_weights(data)[indices]
    baseline_error = target
    corrected_error = target - correction
    return {
        "samples": len(indices),
        "baseline_mse": float(
            np.average(np.square(baseline_error), weights=weights)
        ),
        "corrected_mse": float(
            np.average(np.square(corrected_error), weights=weights)
        ),
        "baseline_mae": float(
            np.average(np.abs(baseline_error), weights=weights)
        ),
        "corrected_mae": float(
            np.average(np.abs(corrected_error), weights=weights)
        ),
        "correction_rms": float(
            np.sqrt(
                np.mean(
                    np.square(correction.astype(np.float64, copy=False))
                )
            )
        ),
    }


def parse_ridges(value: str) -> tuple[float, ...]:
    values = tuple(float(item) for item in value.split(","))
    if not values or any(item < 0.0 or not np.isfinite(item) for item in values):
        raise ValueError("ridge candidates must be finite and non-negative")
    return values


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fit potential-mobility correction tables.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--ridge-candidates", default="1,4,16,64")
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {args.output_dir}"
            )
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)

    base = read_java_model(args.base_model)
    train = load_split(args.dataset_dir / "train.npz")
    validation = load_split(args.dataset_dir / "validation.npz")
    test = load_split(args.dataset_dir / "test.npz")
    ridges = parse_ridges(args.ridge_candidates)

    tables = []
    selections = []
    test_metrics = []
    for phase in range(PHASE_COUNT):
        candidates = []
        for ridge in ridges:
            table = np.rint(
                fit_phase_table(
                    train,
                    phase,
                    base.score_scale,
                    ridge,
                )
            ).astype(np.int16)
            result = metrics(
                validation,
                phase,
                base.score_scale,
                table,
            )
            candidates.append(
                {
                    "ridge": ridge,
                    "validation": result,
                    "table": table,
                }
            )
        selected = min(
            candidates,
            key=lambda item: item["validation"]["corrected_mse"],
        )
        table = selected.pop("table")
        tables.append(table)
        selections.append(selected)
        test_metrics.append(
            {
                "phase": phase,
                **metrics(
                    test,
                    phase,
                    base.score_scale,
                    table,
                ),
            }
        )

    tables_path = args.output_dir / "potential-mobility-tables.npz"
    np.savez_compressed(
        tables_path,
        tables=np.stack(tables),
        score_scale=np.asarray([base.score_scale], dtype=np.int32),
    )
    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "dataset": str(args.dataset_dir),
        "dataset_metadata_sha256": sha256_file(
            args.dataset_dir / "metadata.json"
        ),
        "base_model": str(args.base_model),
        "base_model_sha256": sha256_file(args.base_model),
        "ridge_candidates": ridges,
        "selections": selections,
        "test_metrics": test_metrics,
        "tables": {
            "path": str(tables_path),
            "sha256": sha256_file(tables_path),
            "bytes": tables_path.stat().st_size,
        },
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(test_metrics, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
