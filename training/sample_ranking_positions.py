"""Sample phase-balanced positions for move-ranking experiments."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import numpy as np

from training.othello import legal_moves


PHASE_STARTS = (20, 30, 40, 50)
SPLITS = ("train", "validation", "test")


def phase_ids(ply: np.ndarray) -> np.ndarray:
    return np.searchsorted(
        np.asarray(PHASE_STARTS[1:], dtype=np.int16),
        ply.astype(np.int16),
        side="right",
    )


def sample_indices(
    data: dict[str, np.ndarray],
    phase: int,
    count: int,
    rng: np.random.Generator,
) -> list[int]:
    candidates = np.flatnonzero(phase_ids(data["ply"]) == phase)
    rng.shuffle(candidates)
    selected: list[int] = []
    for raw_index in candidates:
        index = int(raw_index)
        player = int(data["player"][index])
        black = int(data["black"][index])
        white = int(data["white"][index])
        own = black if player == 1 else white
        other = white if player == 1 else black
        if legal_moves(own, other).bit_count() < 2:
            continue
        selected.append(index)
        if len(selected) == count:
            return selected
    raise ValueError(
        f"phase {phase} has only {len(selected)} positions with 2+ legal moves"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sample v4 positions for all-legal-move teacher searches.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/combined-evaluation-v4"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(".training/ranking/positions.tsv"),
    )
    parser.add_argument("--train-per-phase", type=int, default=256)
    parser.add_argument("--validation-per-phase", type=int, default=64)
    parser.add_argument("--test-per-phase", type=int, default=128)
    parser.add_argument("--seed", type=int, default=20260804)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    counts = {
        "train": args.train_per_phase,
        "validation": args.validation_per_phase,
        "test": args.test_per_phase,
    }
    if any(count < 0 for count in counts.values()):
        raise ValueError("sample counts must be non-negative")
    if args.output.exists() and not args.overwrite:
        raise FileExistsError(f"output already exists: {args.output}")
    args.output.parent.mkdir(parents=True, exist_ok=True)

    next_id = 0
    with args.output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(
            (
                "parent_id",
                "split",
                "phase",
                "ply",
                "black",
                "white",
                "player",
                "sample_count",
                "theoretical_count",
            )
        )
        for split_number, split in enumerate(SPLITS):
            path = args.dataset_dir / f"{split}.npz"
            with np.load(path, allow_pickle=False) as archive:
                data = {
                    name: archive[name]
                    for name in (
                        "black",
                        "white",
                        "player",
                        "ply",
                        "sample_count",
                        "theoretical_count",
                    )
                }
                rng = np.random.default_rng(
                    args.seed + 1_000_003 * (split_number + 1)
                )
                for phase in range(len(PHASE_STARTS)):
                    selected = sample_indices(
                        data,
                        phase,
                        counts[split],
                        rng,
                    )
                    for index in selected:
                        writer.writerow(
                            (
                                next_id,
                                split,
                                phase,
                                int(data["ply"][index]),
                                f"{int(data['black'][index]):016x}",
                                f"{int(data['white'][index]):016x}",
                                int(data["player"][index]),
                                int(data["sample_count"][index]),
                                int(data["theoretical_count"][index]),
                            )
                        )
                        next_id += 1
                    print(
                        f"sample {split} phase={phase}: {len(selected)}"
                    )
    print(f"ranking positions written: {args.output} ({next_id})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
