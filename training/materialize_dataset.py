"""Materialize model-specific features from a reusable position corpus."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sys

import numpy as np

from training.build_corpus import NO_THEORETICAL_LABEL, SPLIT_IDS
from training.othello import (
    BLACK,
    CORNERS,
    frontier_counts,
    legal_moves,
    parity_access_difference,
    stable_edge_discs,
)
from training.patterns import PATTERN_GROUPS, encode_group


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def position_keys(data: dict[str, np.ndarray]) -> np.ndarray:
    keys = np.empty(
        len(data["player"]),
        dtype=[("black", "<u8"), ("white", "<u8"), ("player", "i1")],
    )
    keys["black"] = data["black"]
    keys["white"] = data["white"]
    keys["player"] = data["player"]
    return keys


def load_observations(
    corpus_dir: Path,
    metadata: dict[str, object],
    start_ply: int,
    end_ply: int,
    stride: int,
) -> dict[str, dict[str, np.ndarray]]:
    columns: dict[str, dict[str, list[np.ndarray]]] = {
        name: {} for name in SPLIT_IDS
    }
    shard_names = sorted(
        name for name in metadata["files"] if name.startswith("positions-")
    )
    for number, shard_name in enumerate(shard_names, start=1):
        path = corpus_dir / shard_name
        expected = metadata["files"][shard_name]["sha256"]
        if sha256_file(path) != expected:
            raise ValueError(f"corpus shard SHA-256 mismatch: {path}")
        with np.load(path, allow_pickle=False) as archive:
            data = {name: archive[name] for name in archive.files}
        for split_name, split_id in SPLIT_IDS.items():
            selected = (
                (data["split"] == split_id)
                & (data["ply"] >= start_ply)
                & (data["ply"] <= end_ply)
                & ((data["ply"] - start_ply) % stride == 0)
            )
            destination = columns[split_name]
            for name, values in data.items():
                if name == "split":
                    continue
                destination.setdefault(name, []).append(values[selected])
        print(f"load corpus shard {number}/{len(shard_names)}: {shard_name}")

    result = {}
    for split_name, split_columns in columns.items():
        result[split_name] = {
            name: np.concatenate(parts)
            for name, parts in split_columns.items()
        }
    return result


def aggregate_observations(
    data: dict[str, np.ndarray],
) -> dict[str, np.ndarray]:
    if len(data["player"]) == 0:
        raise ValueError("cannot aggregate an empty split")
    order = np.argsort(position_keys(data), order=("black", "white", "player"))
    sorted_data = {name: values[order] for name, values in data.items()}
    keys = position_keys(sorted_data)
    starts = np.concatenate(
        (
            np.asarray([0], dtype=np.int64),
            np.flatnonzero(keys[1:] != keys[:-1]) + 1,
        )
    )
    counts = np.diff(np.append(starts, len(keys))).astype(np.uint32)

    source_bits = np.left_shift(
        np.uint16(1),
        sorted_data["source"].astype(np.uint16),
    )
    theory_valid = sorted_data["theoretical_disc"] != NO_THEORETICAL_LABEL
    theory_count = np.add.reduceat(theory_valid.astype(np.uint32), starts)
    theory_sum = np.add.reduceat(
        np.where(theory_valid, sorted_data["theoretical_disc"], 0).astype(
            np.int64
        ),
        starts,
    )
    label_disc = np.add.reduceat(
        sorted_data["label_disc"].astype(np.float64), starts
    ) / counts
    label_filled = np.add.reduceat(
        sorted_data["label_filled"].astype(np.float64), starts
    ) / counts
    theoretical_disc = np.divide(
        theory_sum,
        theory_count,
        out=np.full(len(starts), np.nan, dtype=np.float64),
        where=theory_count != 0,
    )
    teacher_disc = np.where(theory_count != 0, theoretical_disc, label_disc)
    teacher_filled = np.where(theory_count != 0, theoretical_disc, label_filled)

    final_disc = sorted_data["label_disc"]
    return {
        "black": sorted_data["black"][starts],
        "white": sorted_data["white"][starts],
        "player": sorted_data["player"][starts],
        "ply": sorted_data["ply"][starts],
        "game_id": sorted_data["game_id"][starts],
        "source": sorted_data["source"][starts],
        "source_mask": np.bitwise_or.reduceat(source_bits, starts),
        "sample_count": counts,
        "label_disc": label_disc.astype(np.float32),
        "label_filled": label_filled.astype(np.float32),
        "teacher_disc": teacher_disc.astype(np.float32),
        "teacher_filled": teacher_filled.astype(np.float32),
        "theoretical_count": theory_count,
        "black_wins": np.add.reduceat((final_disc > 0).astype(np.uint32), starts),
        "draws": np.add.reduceat((final_disc == 0).astype(np.uint32), starts),
        "white_wins": np.add.reduceat((final_disc < 0).astype(np.uint32), starts),
    }


def remove_seen_positions(
    data: dict[str, np.ndarray],
    seen: np.ndarray | None,
) -> tuple[dict[str, np.ndarray], np.ndarray, int]:
    keys = position_keys(data)
    if seen is None:
        return data, keys, 0
    duplicate = np.isin(keys, seen, assume_unique=True)
    filtered = {name: values[~duplicate] for name, values in data.items()}
    remaining_keys = keys[~duplicate]
    combined = np.unique(np.concatenate((seen, remaining_keys)))
    return filtered, combined, int(np.count_nonzero(duplicate))


def add_features(
    data: dict[str, np.ndarray],
    split_name: str,
) -> dict[str, np.ndarray]:
    count = len(data["player"])
    result = dict(data)
    result.update(
        {
            "mobility_own": np.empty(count, dtype=np.uint8),
            "mobility_opponent": np.empty(count, dtype=np.uint8),
            "frontier_own": np.empty(count, dtype=np.uint8),
            "frontier_opponent": np.empty(count, dtype=np.uint8),
            "disc_difference": np.empty(count, dtype=np.int8),
            "corner_difference": np.empty(count, dtype=np.int8),
            "corner_move_difference": np.empty(count, dtype=np.int8),
            "stable_edge_difference": np.empty(count, dtype=np.int8),
            "parity_access_difference": np.empty(count, dtype=np.int8),
        }
    )
    for name, group in PATTERN_GROUPS.items():
        result[name] = np.empty((count, group.instances), dtype=np.uint16)

    for index in range(count):
        black = int(data["black"][index])
        white = int(data["white"][index])
        player = int(data["player"][index])
        own = black if player == BLACK else white
        other = white if player == BLACK else black
        own_moves = legal_moves(own, other)
        opponent_moves = legal_moves(other, own)
        own_frontier, opponent_frontier = frontier_counts(own, other)
        occupied = own | other
        empties = 64 - occupied.bit_count()

        result["mobility_own"][index] = own_moves.bit_count()
        result["mobility_opponent"][index] = opponent_moves.bit_count()
        result["frontier_own"][index] = own_frontier
        result["frontier_opponent"][index] = opponent_frontier
        result["disc_difference"][index] = own.bit_count() - other.bit_count()
        result["corner_difference"][index] = (
            (own & CORNERS).bit_count() - (other & CORNERS).bit_count()
        )
        result["corner_move_difference"][index] = (
            (own_moves & CORNERS).bit_count()
            - (opponent_moves & CORNERS).bit_count()
        )
        result["stable_edge_difference"][index] = (
            stable_edge_discs(own, occupied).bit_count()
            - stable_edge_discs(other, occupied).bit_count()
        )
        result["parity_access_difference"][index] = (
            parity_access_difference(own, other, own_moves, opponent_moves)
            if empties <= 20
            else 0
        )
        for name, group in PATTERN_GROUPS.items():
            result[name][index] = encode_group(own, other, group)

        if (index + 1) % 100000 == 0 or index + 1 == count:
            print(f"features {split_name}: {index + 1}/{count}")
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create the current six-pattern dataset from a corpus.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--corpus-dir",
        type=Path,
        default=Path(".training/corpora/othello-public-v1"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/datasets/combined-evaluation-v4"),
    )
    parser.add_argument("--start-ply", type=int, default=8)
    parser.add_argument("--end-ply", type=int, default=52)
    parser.add_argument("--stride", type=int, default=1)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if not 0 <= args.start_ply <= args.end_ply <= 59:
            raise ValueError(
                "ply range must satisfy 0 <= start-ply <= end-ply <= 59"
            )
        if args.stride < 1:
            raise ValueError("stride must be positive")
        corpus_metadata = json.loads(
            (args.corpus_dir / "metadata.json").read_text(encoding="utf-8")
        )
        observations = load_observations(
            args.corpus_dir,
            corpus_metadata,
            args.start_ply,
            args.end_ply,
            args.stride,
        )
        aggregated = {}
        seen = None
        duplicates_removed = {}
        for split_name in SPLIT_IDS:
            print(
                f"aggregate {split_name}: "
                f"{len(observations[split_name]['player'])} observations"
            )
            split = aggregate_observations(observations[split_name])
            split, seen, skipped = remove_seen_positions(split, seen)
            aggregated[split_name] = split
            duplicates_removed[split_name] = skipped
            print(
                f"aggregate {split_name}: {len(split['player'])} unique, "
                f"cross_split_removed={skipped}"
            )

        if args.output_dir.exists():
            if not args.overwrite:
                raise FileExistsError(
                    f"output directory exists; pass --overwrite: {args.output_dir}"
                )
            shutil.rmtree(args.output_dir)
        args.output_dir.mkdir(parents=True)

        files = {}
        stats = {}
        for split_name, split in aggregated.items():
            featured = add_features(split, split_name)
            path = args.output_dir / f"{split_name}.npz"
            np.savez_compressed(path, **featured)
            files[path.name] = sha256_file(path)
            source_stats = {}
            for source_name, source in corpus_metadata["sources"].items():
                source_id = int(source["id"])
                source_stats[source_name] = {
                    "positions": int(np.count_nonzero(featured["source"] == source_id)),
                    "weighted_observations": int(
                        featured["sample_count"][featured["source"] == source_id].sum()
                    ),
                }
            stats[split_name] = {
                "positions": len(featured["player"]),
                "observations": int(featured["sample_count"].sum()),
                "duplicate_observations_aggregated": int(
                    featured["sample_count"].sum() - len(featured["player"])
                ),
                "cross_split_positions_removed": duplicates_removed[split_name],
                "theoretical_positions": int(
                    np.count_nonzero(featured["theoretical_count"])
                ),
                "sources": source_stats,
            }

        metadata = {
            "dataset_format": 4,
            "name": "combined-evaluation-v4",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "corpus": {
                "path": str(args.corpus_dir),
                "name": corpus_metadata["name"],
                "metadata_sha256": sha256_file(args.corpus_dir / "metadata.json"),
            },
            "sources": corpus_metadata["sources"],
            "sampling": {
                "start_ply": args.start_ply,
                "end_ply": args.end_ply,
                "stride": args.stride,
                "corpus_sampling": corpus_metadata["sampling"],
            },
            "split": {
                **corpus_metadata["split"],
                "exact_position_policy": (
                    "aggregate within split; train then validation then test priority"
                ),
            },
            "labels": {
                "label_disc": "mean actual final disc difference from black perspective",
                "label_filled": "mean actual filled difference from black perspective",
                "teacher_disc": "WTHOR theoretical value when present, else label_disc",
                "teacher_filled": "WTHOR theoretical value when present, else label_filled",
                "sample_count": "number of game observations represented by the row",
                "wld_counts": "black_wins, draws, and white_wins preserve soft outcomes",
            },
            "features": {
                "patterns": list(PATTERN_GROUPS),
                "derivation": "materialized from raw bitboards; safe to replace",
            },
            "stats": stats,
            "files": files,
        }
        (args.output_dir / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(stats, ensure_ascii=False, indent=2))
        print(f"dataset written to {args.output_dir}")
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
