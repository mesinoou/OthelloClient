"""Analyze paired-opening match logs with cluster bootstrap intervals."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

import numpy as np


GAME_PATTERN = re.compile(
    r"^game=\d+/\d+ opening=(\d+) .* "
    r"result=(WIN|DRAW|LOSS) margin=([+-]?\d+) "
)
RESULT_SCORE = {"WIN": 1.0, "DRAW": 0.5, "LOSS": 0.0}


def read_opening_pairs(path: Path) -> tuple[np.ndarray, np.ndarray]:
    grouped: dict[int, list[tuple[float, float]]] = {}
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("cp932")
    for line in text.splitlines():
        match = GAME_PATTERN.match(line)
        if match is None:
            continue
        opening = int(match.group(1))
        grouped.setdefault(opening, []).append(
            (
                RESULT_SCORE[match.group(2)],
                float(match.group(3)),
            )
        )
    if not grouped:
        raise ValueError(f"{path}: no game rows found")
    expected = list(range(1, len(grouped) + 1))
    if sorted(grouped) != expected:
        raise ValueError(f"{path}: opening numbers are incomplete")
    if any(len(games) != 2 for games in grouped.values()):
        raise ValueError(f"{path}: every opening must contain two colors")
    scores = np.asarray(
        [sum(score for score, _ in grouped[index]) / 2 for index in expected],
        dtype=np.float64,
    )
    margins = np.asarray(
        [sum(margin for _, margin in grouped[index]) / 2 for index in expected],
        dtype=np.float64,
    )
    return scores, margins


def bootstrap_interval(
    values: np.ndarray,
    samples: int,
    rng: np.random.Generator,
) -> tuple[float, float]:
    draws_per_batch = max(1, min(samples, 1_000_000 // len(values)))
    means = np.empty(samples, dtype=np.float64)
    completed = 0
    while completed < samples:
        count = min(draws_per_batch, samples - completed)
        selected = rng.integers(
            0,
            len(values),
            size=(count, len(values)),
        )
        means[completed : completed + count] = values[selected].mean(axis=1)
        completed += count
    lower, upper = np.quantile(means, (0.025, 0.975))
    return float(lower), float(upper)


def summarize(
    candidate_path: Path,
    baseline_path: Path | None,
    samples: int,
    seed: int,
) -> dict[str, object]:
    candidate_score, candidate_margin = read_opening_pairs(candidate_path)
    rng = np.random.default_rng(seed)
    score_interval = bootstrap_interval(candidate_score, samples, rng)
    margin_interval = bootstrap_interval(candidate_margin, samples, rng)
    result: dict[str, object] = {
        "candidate": str(candidate_path),
        "opening_pairs": len(candidate_score),
        "games": len(candidate_score) * 2,
        "score_rate": float(candidate_score.mean()),
        "score_rate_95_ci": score_interval,
        "mean_margin": float(candidate_margin.mean()),
        "mean_margin_95_ci": margin_interval,
        "bootstrap_samples": samples,
        "seed": seed,
    }
    if baseline_path is not None:
        baseline_score, baseline_margin = read_opening_pairs(baseline_path)
        if len(candidate_score) != len(baseline_score):
            raise ValueError("candidate and baseline opening counts differ")
        score_difference = candidate_score - baseline_score
        margin_difference = candidate_margin - baseline_margin
        result["baseline"] = str(baseline_path)
        result["score_rate_difference"] = float(score_difference.mean())
        result["score_rate_difference_95_ci"] = bootstrap_interval(
            score_difference,
            samples,
            rng,
        )
        result["mean_margin_difference"] = float(margin_difference.mean())
        result["mean_margin_difference_95_ci"] = bootstrap_interval(
            margin_difference,
            samples,
            rng,
        )
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Bootstrap opening-paired Othello match results.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--baseline", type=Path, default=None)
    parser.add_argument("--samples", type=int, default=200_000)
    parser.add_argument("--seed", type=int, default=20260805)
    parser.add_argument("--output", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.samples < 1:
        raise ValueError("bootstrap samples must be positive")
    result = summarize(
        args.candidate,
        args.baseline,
        args.samples,
        args.seed,
    )
    text = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    if args.output is None:
        print(text, end="")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
        print(f"match analysis written: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
