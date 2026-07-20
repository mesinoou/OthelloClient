"""Verify dataset hashes, array invariants, splits, and sampled features."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

import numpy as np

from training.othello import BLACK, frontier_counts, legal_moves
from training.patterns import PATTERN_GROUPS, encode_group


REQUIRED_ARRAYS = {
    "black",
    "white",
    "player",
    "ply",
    "game_id",
    "label_disc",
    "label_filled",
    "mobility",
    "frontier_black",
    "frontier_white",
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


def verify_arrays(name: str, data: dict[str, np.ndarray]) -> None:
    count = len(data["player"])
    for field, values in data.items():
        if len(values) != count:
            raise ValueError(f"{name}: {field} length differs from player")
    if np.any(data["black"] & data["white"]):
        raise ValueError(f"{name}: overlapping black and white bitboards")
    if not np.all(np.isin(data["player"], (-1, 1))):
        raise ValueError(f"{name}: invalid player value")
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
    for pattern_name, patterns in PATTERN_GROUPS.items():
        values = data[pattern_name]
        expected_shape = (count, len(patterns))
        if values.shape != expected_shape:
            raise ValueError(
                f"{name}: {pattern_name} shape {values.shape}, "
                f"expected {expected_shape}"
            )
        if np.any(values >= 3 ** len(patterns[0])):
            raise ValueError(f"{name}: {pattern_name} index out of range")


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
        expected_mobility = legal_moves(own, other).bit_count() * player
        if expected_mobility != int(data["mobility"][index]):
            raise ValueError(f"{name}: mobility mismatch at index {index}")
        expected_frontier = frontier_counts(black, white)
        actual_frontier = (
            int(data["frontier_black"][index]),
            int(data["frontier_white"][index]),
        )
        if expected_frontier != actual_frontier:
            raise ValueError(f"{name}: frontier mismatch at index {index}")
        for pattern_name, patterns in PATTERN_GROUPS.items():
            expected = encode_group(black, white, patterns)
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
        default=Path(".training/datasets/nyanyan-self-play-v1"),
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
        splits = {}
        for name in ("train", "validation", "test"):
            path = args.dataset_dir / f"{name}.npz"
            expected_hash = metadata["files"][path.name]
            actual_hash = sha256_file(path)
            if actual_hash != expected_hash:
                raise ValueError(f"{path}: SHA-256 mismatch")
            data = load_split(path)
            verify_arrays(name, data)
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

        report = {}
        boundaries = np.asarray([30, 40, 50], dtype=np.uint8)
        for name, data in splits.items():
            phases = np.searchsorted(boundaries, data["ply"], side="right")
            report[name] = {
                "positions": len(data["ply"]),
                "phase_counts": np.bincount(phases, minlength=4).tolist(),
                "mobility_range": [
                    int(data["mobility"].min()),
                    int(data["mobility"].max()),
                ],
            }
        print(json.dumps(report, indent=2))
        print("dataset verification: PASS")
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
