"""Evaluate static model move ordering against deeper-search teacher scores."""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
from typing import Iterable

import numpy as np


def average_ranks(values: np.ndarray) -> np.ndarray:
    order = np.argsort(values, kind="stable")
    ranks = np.empty(len(values), dtype=np.float64)
    start = 0
    while start < len(values):
        end = start + 1
        while end < len(values) and values[order[end]] == values[order[start]]:
            end += 1
        ranks[order[start:end]] = (start + end - 1) / 2.0
        start = end
    return ranks


def rank_correlation(left: np.ndarray, right: np.ndarray) -> float:
    if len(left) < 2:
        return 1.0
    left_rank = average_ranks(left)
    right_rank = average_ranks(right)
    if np.all(left_rank == left_rank[0]) or np.all(right_rank == right_rank[0]):
        return 0.0
    return float(np.corrcoef(left_rank, right_rank)[0, 1])


def pairwise_accuracy(predicted: np.ndarray, target: np.ndarray) -> float:
    correct = 0.0
    compared = 0
    for first in range(len(target)):
        for second in range(first + 1, len(target)):
            target_sign = np.sign(target[first] - target[second])
            if target_sign == 0:
                continue
            predicted_sign = np.sign(predicted[first] - predicted[second])
            correct += 0.5 if predicted_sign == 0 else float(
                predicted_sign == target_sign
            )
            compared += 1
    return correct / compared if compared else 1.0


def top_set(values: np.ndarray) -> set[int]:
    maximum = np.max(values)
    return set(np.flatnonzero(values == maximum).tolist())


def summarize(groups: Iterable[list[dict[str, str]]], column: str) -> dict:
    top1 = []
    pairwise = []
    correlations = []
    regrets = []
    stable = []
    stable_top1 = []
    for rows in groups:
        target = np.asarray([int(row["deep_score"]) for row in rows])
        shallow = np.asarray([int(row["shallow_score"]) for row in rows])
        predicted = np.asarray([int(row[column]) for row in rows])
        target_top = top_set(target)
        predicted_top = top_set(predicted)
        is_stable = bool(target_top & top_set(shallow))
        hit = bool(target_top & predicted_top)
        top1.append(hit)
        pairwise.append(pairwise_accuracy(predicted, target))
        correlations.append(rank_correlation(predicted, target))
        regrets.append(
            int(np.max(target)) - int(np.max(target[list(predicted_top)]))
        )
        stable.append(is_stable)
        if is_stable:
            stable_top1.append(hit)
    return {
        "positions": len(top1),
        "teacher_stable": float(np.mean(stable)),
        "top1_accuracy": float(np.mean(top1)),
        "stable_top1_accuracy": (
            float(np.mean(stable_top1)) if stable_top1 else None
        ),
        "pairwise_accuracy": float(np.mean(pairwise)),
        "rank_correlation": float(np.mean(correlations)),
        "mean_regret": float(np.mean(regrets)),
        "median_regret": float(np.median(regrets)),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Measure static child scores against deep search rankings."
    )
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    grouped: dict[int, list[dict[str, str]]] = defaultdict(list)
    with args.input.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError("ranking file has no header")
        model_columns = ["shallow_score"] + [
            name for name in reader.fieldnames if name.startswith("static_")
        ]
        if len(model_columns) == 1:
            raise ValueError("ranking file has no static model columns")
        for row in reader:
            grouped[int(row["parent_id"])].append(row)

    partitions: dict[str, list[list[dict[str, str]]]] = defaultdict(list)
    for rows in grouped.values():
        split = rows[0]["split"]
        phase = rows[0]["phase"]
        partitions["all"].append(rows)
        partitions[f"split:{split}"].append(rows)
        partitions[f"phase:{phase}"].append(rows)
        partitions[f"split:{split}/phase:{phase}"].append(rows)

    result = {
        partition: {
            (
                "teacher_shallow"
                if column == "shallow_score"
                else column.removeprefix("static_")
            ): summarize(rows, column)
            for column in model_columns
        }
        for partition, rows in sorted(partitions.items())
    }
    rendered = json.dumps(result, indent=2, ensure_ascii=False)
    print(rendered)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
