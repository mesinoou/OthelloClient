"""Fit and validate phase/depth Multi-ProbCut calibration parameters."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path


T_SIGMA = 2.5
TAIL_RISK = 0.01


def load(
    path: Path,
    reduction: int,
) -> dict[tuple[int, int], list[tuple[float, float]]]:
    grouped: dict[tuple[int, int], list[tuple[float, float]]] = defaultdict(list)
    positions: dict[tuple[int, int, int], dict[int, tuple[float, float]]] = (
        defaultdict(dict)
    )
    with path.open(encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            phase = int(row["phase"])
            depth = int(row["depth"])
            shallow = float(row["shallowScore"])
            deep = float(row["deepScore"])
            if reduction == 2:
                grouped[(phase, depth)].append((shallow, deep))
            positions[(int(row["seed"]), phase, int(row["sample"]))][depth] = (
                shallow,
                deep,
            )
    if reduction == 4:
        for (_, phase, _), scores in positions.items():
            if 6 in scores and 8 in scores:
                grouped[(phase, 8)].append((scores[6][0], scores[8][1]))
            if 6 in scores and 10 in scores:
                grouped[(phase, 10)].append((scores[6][1], scores[10][1]))
    return grouped


def fit(samples: list[tuple[float, float]]) -> tuple[float, float, float, float]:
    count = len(samples)
    mean_x = sum(x for x, _ in samples) / count
    mean_y = sum(y for _, y in samples) / count
    variance_x = sum((x - mean_x) ** 2 for x, _ in samples)
    if variance_x == 0.0:
        raise ValueError("zero shallow-score variance")
    slope = sum((x - mean_x) * (y - mean_y) for x, y in samples) / variance_x
    intercept = mean_y - slope * mean_x
    residuals = [y - (slope * x + intercept) for x, y in samples]
    sigma = math.sqrt(sum(value * value for value in residuals) / (count - 2))
    total_y = sum((y - mean_y) ** 2 for _, y in samples)
    r_squared = 1.0 - sum(value * value for value in residuals) / total_y
    return slope, intercept, sigma, r_squared


def assess(
    samples: list[tuple[float, float]],
    slope: float,
    intercept: float,
    sigma: float,
) -> dict[str, float | int]:
    residuals = [y - (slope * x + intercept) for x, y in samples]
    threshold = T_SIGMA * sigma
    false_high_risk = sum(value < -threshold for value in residuals)
    false_low_risk = sum(value > threshold for value in residuals)
    return {
        "samples": len(samples),
        "mse": sum(value * value for value in residuals) / len(residuals),
        "mae": sum(abs(value) for value in residuals) / len(residuals),
        "false_high_risk": false_high_risk,
        "false_high_rate": false_high_risk / len(residuals),
        "false_low_risk": false_low_risk,
        "false_low_rate": false_low_risk / len(residuals),
    }


def conformal_margins(
    samples: list[tuple[float, float]],
    slope: float,
    intercept: float,
) -> tuple[float, float]:
    false_high_errors = sorted(
        slope * x + intercept - y for x, y in samples
    )
    false_low_errors = sorted(
        y - (slope * x + intercept) for x, y in samples
    )
    rank = math.ceil((len(samples) + 1) * (1.0 - TAIL_RISK)) - 1
    index = min(len(samples) - 1, max(0, rank))
    return false_high_errors[index], false_low_errors[index]


def assess_margins(
    samples: list[tuple[float, float]],
    slope: float,
    intercept: float,
    false_high_margin: float,
    false_low_margin: float,
) -> dict[str, float | int]:
    residuals = [y - (slope * x + intercept) for x, y in samples]
    false_high_risk = sum(
        value < -false_high_margin for value in residuals
    )
    false_low_risk = sum(value > false_low_margin for value in residuals)
    return {
        "samples": len(samples),
        "false_high_risk": false_high_risk,
        "false_high_rate": false_high_risk / len(samples),
        "false_low_risk": false_low_risk,
        "false_low_rate": false_low_risk / len(samples),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--holdout", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reduction", type=int, choices=(2, 4), default=2)
    args = parser.parse_args()

    train = load(args.train, args.reduction)
    validation = load(args.validation, args.reduction)
    holdout = load(args.holdout, args.reduction)
    if train.keys() != validation.keys() or train.keys() != holdout.keys():
        raise ValueError("calibration group mismatch")

    groups = []
    for phase, depth in sorted(train):
        slope, intercept, sigma, r_squared = fit(train[(phase, depth)])
        false_high_margin, false_low_margin = conformal_margins(
            train[(phase, depth)], slope, intercept
        )
        validation_stats = assess(
            validation[(phase, depth)], slope, intercept, sigma
        )
        holdout_stats = assess(holdout[(phase, depth)], slope, intercept, sigma)
        validation_conformal = assess_margins(
            validation[(phase, depth)],
            slope,
            intercept,
            false_high_margin,
            false_low_margin,
        )
        holdout_conformal = assess_margins(
            holdout[(phase, depth)],
            slope,
            intercept,
            false_high_margin,
            false_low_margin,
        )
        enabled = (
            slope > 0.0
            and validation_stats["false_high_rate"] < 0.01
            and validation_stats["false_low_rate"] < 0.01
            and holdout_stats["false_high_rate"] < 0.01
            and holdout_stats["false_low_rate"] < 0.01
        )
        groups.append(
            {
                "phase": phase,
                "depth": depth,
                "slope": slope,
                "intercept": intercept,
                "sigma": sigma,
                "r_squared": r_squared,
                "t_sigma": T_SIGMA,
                "enabled": enabled,
                "false_high_margin": false_high_margin,
                "false_low_margin": false_low_margin,
                "train": assess(train[(phase, depth)], slope, intercept, sigma),
                "validation": validation_stats,
                "holdout": holdout_stats,
                "validation_conformal": validation_conformal,
                "holdout_conformal": holdout_conformal,
            }
        )

    result = {
        "format": "mpc-calibration-v1",
        "train": str(args.train),
        "validation": str(args.validation),
        "holdout": str(args.holdout),
        "reduction": args.reduction,
        "groups": groups,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for group in groups:
        print(
            "phase={phase} depth={depth} a={slope:.6f} b={intercept:.2f} "
            "sigma={sigma:.2f} r2={r_squared:.4f} "
            "gaussian_validation={vhi}/{vlo} gaussian_holdout={hhi}/{hlo} "
            "margins={false_high_margin:.1f}/{false_low_margin:.1f} "
            "conformal_validation={cvhi}/{cvlo} "
            "conformal_holdout={chhi}/{chlo} enabled={enabled}".format(
                **group,
                vhi=group["validation"]["false_high_risk"],
                vlo=group["validation"]["false_low_risk"],
                hhi=group["holdout"]["false_high_risk"],
                hlo=group["holdout"]["false_low_risk"],
                cvhi=group["validation_conformal"]["false_high_risk"],
                cvlo=group["validation_conformal"]["false_low_risk"],
                chhi=group["holdout_conformal"]["false_high_risk"],
                chlo=group["holdout_conformal"]["false_low_risk"],
            )
        )


if __name__ == "__main__":
    main()
