"""Verify dataset hashes, array invariants, splits, and sampled features."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np

from training.othello import (
    BLACK,
    CORNERS,
    frontier_counts,
    legal_moves,
    parity_access_difference,
    stable_edge_discs,
)
from training.patterns import PATTERN_GROUPS, encode_group


REQUIRED_ARRAYS = {
    "black",
    "white",
    "player",
    "ply",
    "game_id",
    "label_disc",
    "label_filled",
    "mobility_own",
    "mobility_opponent",
    "frontier_own",
    "frontier_opponent",
    "disc_difference",
    "corner_difference",
    "corner_move_difference",
    "stable_edge_difference",
    "parity_access_difference",
    *PATTERN_GROUPS.keys(),
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_split(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        missing = REQUIRED_ARRAYS - set(archive.files)
        if missing:
            raise ValueError(f"{path}: missing arrays: {sorted(missing)}")
        return {name: archive[name] for name in archive.files}


def position_keys(data: dict[str, np.ndarray]) -> np.ndarray:
    keys = np.empty(
        len(data["player"]),
        dtype=[("black", "<u8"), ("white", "<u8"), ("player", "i1")],
    )
    keys["black"] = data["black"]
    keys["white"] = data["white"]
    keys["player"] = data["player"]
    return np.unique(keys)


def verify_arrays(
    name: str,
    data: dict[str, np.ndarray],
    source_ids: set[int] | None = None,
) -> None:
    count = len(data["player"])
    for field, values in data.items():
        if len(values) != count:
            raise ValueError(f"{name}: {field} length differs from player")
    if np.any(data["black"] & data["white"]):
        raise ValueError(f"{name}: overlapping black and white bitboards")
    if not np.all(np.isin(data["player"], (-1, 1))):
        raise ValueError(f"{name}: invalid player value")
    if source_ids is not None:
        if "source" not in data:
            raise ValueError(f"{name}: missing source array")
        if not np.all(np.isin(data["source"], tuple(source_ids))):
            raise ValueError(f"{name}: invalid source value")
    v4_arrays = {
        "source_mask",
        "sample_count",
        "teacher_disc",
        "teacher_filled",
        "theoretical_count",
        "black_wins",
        "draws",
        "white_wins",
    }
    if v4_arrays & set(data):
        missing_v4 = v4_arrays - set(data)
        if missing_v4:
            raise ValueError(f"{name}: incomplete v4 arrays: {sorted(missing_v4)}")
        if np.any(data["sample_count"] == 0):
            raise ValueError(f"{name}: zero sample count")
        wld_count = data["black_wins"] + data["draws"] + data["white_wins"]
        if not np.array_equal(wld_count, data["sample_count"]):
            raise ValueError(f"{name}: WLD counts differ from sample count")
        if np.any(data["theoretical_count"] > data["sample_count"]):
            raise ValueError(f"{name}: theoretical count exceeds sample count")
        for label in ("label_disc", "label_filled", "teacher_disc", "teacher_filled"):
            if not np.isfinite(data[label]).all():
                raise ValueError(f"{name}: non-finite {label}")
            if np.any(np.abs(data[label]) > 64):
                raise ValueError(f"{name}: {label} outside [-64, 64]")
        source_bit = np.left_shift(
            np.uint16(1),
            data["source"].astype(np.uint16),
        )
        if np.any((data["source_mask"] & source_bit) == 0):
            raise ValueError(f"{name}: primary source missing from source mask")
    occupied = np.fromiter(
        (int(value).bit_count() for value in data["black"] | data["white"]),
        dtype=np.uint8,
        count=count,
    )
    if not np.array_equal(occupied, data["ply"] + 4):
        raise ValueError(f"{name}: occupied count does not equal ply + 4")
    if np.any(np.abs(data["label_disc"].astype(np.int16)) > 64):
        raise ValueError(f"{name}: disc label outside [-64, 64]")
    if np.any(np.abs(data["label_filled"].astype(np.int16)) > 64):
        raise ValueError(f"{name}: filled label outside [-64, 64]")
    for pattern_name, group in PATTERN_GROUPS.items():
        values = data[pattern_name]
        expected_shape = (count, group.instances)
        if values.shape != expected_shape:
            raise ValueError(
                f"{name}: {pattern_name} shape {values.shape}, "
                f"expected {expected_shape}"
            )
        for column, pattern in enumerate(group.patterns):
            if np.any(values[:, column] >= 3 ** len(pattern)):
                raise ValueError(
                    f"{name}: {pattern_name}[{column}] index out of range"
                )


def verify_sampled_features(
    name: str,
    data: dict[str, np.ndarray],
    sample_size: int,
    seed: int,
) -> None:
    rng = np.random.default_rng(seed)
    count = min(sample_size, len(data["player"]))
    indices = rng.choice(len(data["player"]), size=count, replace=False)
    for index in indices:
        black = int(data["black"][index])
        white = int(data["white"][index])
        player = int(data["player"][index])
        own = black if player == BLACK else white
        other = white if player == BLACK else black
        own_moves = legal_moves(own, other)
        opponent_moves = legal_moves(other, own)
        expected_mobility = (own_moves.bit_count(), opponent_moves.bit_count())
        actual_mobility = (
            int(data["mobility_own"][index]),
            int(data["mobility_opponent"][index]),
        )
        if expected_mobility != actual_mobility:
            raise ValueError(f"{name}: mobility mismatch at index {index}")
        expected_frontier = frontier_counts(own, other)
        actual_frontier = (
            int(data["frontier_own"][index]),
            int(data["frontier_opponent"][index]),
        )
        if expected_frontier != actual_frontier:
            raise ValueError(f"{name}: frontier mismatch at index {index}")
        occupied = own | other
        expected_scalars = {
            "disc_difference": own.bit_count() - other.bit_count(),
            "corner_difference": (
                (own & CORNERS).bit_count() - (other & CORNERS).bit_count()
            ),
            "corner_move_difference": (
                (own_moves & CORNERS).bit_count()
                - (opponent_moves & CORNERS).bit_count()
            ),
            "stable_edge_difference": (
                stable_edge_discs(own, occupied).bit_count()
                - stable_edge_discs(other, occupied).bit_count()
            ),
            "parity_access_difference": (
                parity_access_difference(
                    own,
                    other,
                    own_moves,
                    opponent_moves,
                )
                if 64 - occupied.bit_count() <= 20
                else 0
            ),
        }
        for feature, expected in expected_scalars.items():
            if expected != int(data[feature][index]):
                raise ValueError(
                    f"{name}: {feature} mismatch at index {index}"
                )
        for pattern_name, group in PATTERN_GROUPS.items():
            expected = encode_group(own, other, group)
            actual = tuple(int(value) for value in data[pattern_name][index])
            if expected != actual:
                raise ValueError(
                    f"{name}: {pattern_name} mismatch at index {index}"
                )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify a generated dataset.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/combined-evaluation-v4"),
        help="dataset directory to verify",
    )
    parser.add_argument(
        "--sample-size",
        type=int,
        default=10000,
        help="positions per split to recompute",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=20260720,
        help="feature verification sampling seed",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        metadata = json.loads(
            (args.dataset_dir / "metadata.json").read_text(encoding="utf-8")
        )
        source_ids = None
        if metadata.get("dataset_format", 1) >= 2:
            source_ids = {
                int(source["id"]) for source in metadata["sources"].values()
            }
        splits = {}
        for name in ("train", "validation", "test"):
            path = args.dataset_dir / f"{name}.npz"
            expected_hash = metadata["files"][path.name]
            actual_hash = sha256_file(path)
            if actual_hash != expected_hash:
                raise ValueError(f"{path}: SHA-256 mismatch")
            data = load_split(path)
            verify_arrays(name, data, source_ids)
            verify_sampled_features(name, data, args.sample_size, args.seed)
            splits[name] = data

        unique_keys = {name: position_keys(data) for name, data in splits.items()}
        for left, right in (
            ("train", "validation"),
            ("train", "test"),
            ("validation", "test"),
        ):
            overlap = np.intersect1d(
                unique_keys[left],
                unique_keys[right],
                assume_unique=True,
            )
            if len(overlap) != 0:
                raise ValueError(f"{left}/{right}: {len(overlap)} positions overlap")
            game_overlap = np.intersect1d(
                np.unique(splits[left]["game_id"]),
                np.unique(splits[right]["game_id"]),
                assume_unique=True,
            )
            if len(game_overlap) != 0:
                raise ValueError(
                    f"{left}/{right}: {len(game_overlap)} games overlap"
                )

        report = {}
        boundaries = np.asarray([30, 40, 50], dtype=np.uint8)
        for name, data in splits.items():
            phases = np.searchsorted(boundaries, data["ply"], side="right")
            report[name] = {
                "positions": len(data["ply"]),
                "phase_counts": np.bincount(phases, minlength=4).tolist(),
                "mobility_own_range": [
                    int(data["mobility_own"].min()),
                    int(data["mobility_own"].max()),
                ],
                "mobility_opponent_range": [
                    int(data["mobility_opponent"].min()),
                    int(data["mobility_opponent"].max()),
                ],
            }
            if source_ids is not None:
                report[name]["source_counts"] = {
                    source_name: int(
                        np.count_nonzero(data["source"] == source["id"])
                    )
                    for source_name, source in metadata["sources"].items()
                }
                expected_counts = {
                    source_name: int(source["positions"])
                    for source_name, source in metadata["stats"][name][
                        "sources"
                    ].items()
                }
                if report[name]["source_counts"] != expected_counts:
                    raise ValueError(f"{name}: source position counts differ")
            if metadata.get("dataset_format", 1) >= 4:
                observations = int(data["sample_count"].sum())
                expected_observations = int(metadata["stats"][name]["observations"])
                if observations != expected_observations:
                    raise ValueError(f"{name}: observation count differs")
                theoretical = int(np.count_nonzero(data["theoretical_count"]))
                expected_theoretical = int(
                    metadata["stats"][name]["theoretical_positions"]
                )
                if theoretical != expected_theoretical:
                    raise ValueError(f"{name}: theoretical position count differs")
                report[name]["observations"] = observations
                report[name]["theoretical_positions"] = theoretical
        print(json.dumps(report, indent=2))
        print("dataset verification: PASS")
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
