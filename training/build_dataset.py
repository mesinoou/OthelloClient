"""Download self-play records and build a leak-resistant NumPy dataset."""

from __future__ import annotations

import argparse
from array import array
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sys
import urllib.request

import numpy as np

from training.othello import (
    BLACK,
    InvalidRecordError,
    Position,
    frontier_counts,
    legal_moves,
    replay_record,
)
from training.patterns import PATTERN_GROUPS, encode_group


SOURCE_REPOSITORY = "Nyanyan/OthelloAI_Textbook"
SOURCE_COMMIT = "ca3dbb5bd39825ea8f6c9526243e548239066873"
SOURCE_LICENSE = "MIT"
SOURCE_FILE_COUNT = 20
SOURCE_URL = (
    "https://raw.githubusercontent.com/"
    f"{SOURCE_REPOSITORY}/{SOURCE_COMMIT}/evaluation/self_play"
)

SPLIT_NAMES = ("train", "validation", "test")


@dataclass
class SplitStats:
    games: int = 0
    positions: int = 0
    duplicate_positions_skipped: int = 0
    passes: int = 0
    black_wins: int = 0
    draws: int = 0
    white_wins: int = 0


class DatasetBuffers:
    def __init__(self) -> None:
        self.black = array("Q")
        self.white = array("Q")
        self.player = array("b")
        self.ply = array("B")
        self.game_id = array("I")
        self.label_disc = array("b")
        self.label_filled = array("b")
        self.mobility = array("b")
        self.frontier_black = array("B")
        self.frontier_white = array("B")
        self.patterns = {
            name: array("H") for name in PATTERN_GROUPS
        }

    def __len__(self) -> int:
        return len(self.player)

    def append(
        self,
        position: Position,
        game_id: int,
        label_disc: int,
        label_filled: int,
    ) -> None:
        self.black.append(position.black)
        self.white.append(position.white)
        self.player.append(position.player)
        self.ply.append(position.ply)
        self.game_id.append(game_id)
        self.label_disc.append(label_disc)
        self.label_filled.append(label_filled)

        own = position.black if position.player == BLACK else position.white
        other = position.white if position.player == BLACK else position.black
        signed_mobility = legal_moves(own, other).bit_count()
        self.mobility.append(signed_mobility * position.player)

        black_frontier, white_frontier = frontier_counts(
            position.black,
            position.white,
        )
        self.frontier_black.append(black_frontier)
        self.frontier_white.append(white_frontier)

        for name, patterns in PATTERN_GROUPS.items():
            self.patterns[name].extend(
                encode_group(position.black, position.white, patterns)
            )

    def arrays(self) -> dict[str, np.ndarray]:
        count = len(self)
        result = {
            "black": _from_array(self.black, np.uint64),
            "white": _from_array(self.white, np.uint64),
            "player": _from_array(self.player, np.int8),
            "ply": _from_array(self.ply, np.uint8),
            "game_id": _from_array(self.game_id, np.uint32),
            "label_disc": _from_array(self.label_disc, np.int8),
            "label_filled": _from_array(self.label_filled, np.int8),
            "mobility": _from_array(self.mobility, np.int8),
            "frontier_black": _from_array(
                self.frontier_black,
                np.uint8,
            ),
            "frontier_white": _from_array(
                self.frontier_white,
                np.uint8,
            ),
        }
        for name, values in self.patterns.items():
            width = len(PATTERN_GROUPS[name])
            result[name] = _from_array(values, np.uint16).reshape(count, width)
        return result


def _from_array(values: array, dtype: np.dtype) -> np.ndarray:
    return np.frombuffer(values, dtype=dtype).copy()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build pattern-evaluation data from public self-play games.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path(".training/sources/OthelloAI_Textbook"),
        help="downloaded self-play record directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/datasets/nyanyan-self-play-v1"),
        help="generated dataset directory",
    )
    parser.add_argument(
        "--source-files",
        type=int,
        default=SOURCE_FILE_COUNT,
        help="number of upstream record files to read",
    )
    parser.add_argument(
        "--max-games",
        type=int,
        default=None,
        help="maximum games to process; useful for smoke tests",
    )
    parser.add_argument(
        "--start-ply",
        type=int,
        default=8,
        help="first ply to sample",
    )
    parser.add_argument(
        "--end-ply",
        type=int,
        default=52,
        help="last ply to sample",
    )
    parser.add_argument(
        "--stride",
        type=int,
        default=1,
        help="sample one position every N plies",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=20260720,
        help="deterministic game-split seed",
    )
    parser.add_argument(
        "--split",
        default="80,10,10",
        help="train,validation,test split weights",
    )
    parser.add_argument(
        "--no-download",
        action="store_true",
        help="require existing source files instead of downloading",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace the output directory if it exists",
    )
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> tuple[int, int, int]:
    if not 0 <= args.start_ply <= args.end_ply <= 60:
        raise ValueError("ply range must satisfy 0 <= start <= end <= 60")
    if args.stride < 1:
        raise ValueError("stride must be positive")
    if not 1 <= args.source_files <= SOURCE_FILE_COUNT:
        raise ValueError(f"source-files must be between 1 and {SOURCE_FILE_COUNT}")
    if args.max_games is not None and args.max_games < 1:
        raise ValueError("max-games must be positive")

    parts = tuple(int(value) for value in args.split.split(","))
    if len(parts) != 3 or any(value <= 0 for value in parts):
        raise ValueError("split must contain three positive integers")
    return parts


def download_sources(source_dir: Path, file_count: int) -> list[Path]:
    source_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for number in range(file_count):
        filename = f"{number:07d}.txt"
        path = source_dir / filename
        paths.append(path)
        if path.is_file() and path.stat().st_size > 0:
            continue
        url = f"{SOURCE_URL}/{filename}"
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "mesinoou-OthelloClient-dataset-builder/1"},
        )
        print(f"download {url}")
        with urllib.request.urlopen(request, timeout=60) as response:
            content = response.read()
        if not content:
            raise IOError(f"downloaded an empty source file: {url}")
        path.write_bytes(content)
    return paths


def load_records(paths: list[Path], max_games: int | None) -> list[tuple[int, str]]:
    records: list[tuple[int, str]] = []
    game_id = 0
    for path in paths:
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(),
            start=1,
        ):
            record = line.strip().lower()
            if not record:
                continue
            if len(record) % 2 != 0:
                raise InvalidRecordError(
                    f"{path}:{line_number}: record length is odd"
                )
            records.append((game_id, record))
            game_id += 1
            if max_games is not None and len(records) >= max_games:
                return records
    return records


def split_for_game(
    game_id: int,
    seed: int,
    weights: tuple[int, int, int],
) -> str:
    payload = f"{seed}:{game_id}".encode("ascii")
    bucket = int.from_bytes(hashlib.sha256(payload).digest()[:8], "big")
    bucket %= sum(weights)
    if bucket < weights[0]:
        return "train"
    if bucket < weights[0] + weights[1]:
        return "validation"
    return "test"


def build(
    records: list[tuple[int, str]],
    args: argparse.Namespace,
    split_weights: tuple[int, int, int],
) -> tuple[dict[str, DatasetBuffers], dict[str, SplitStats]]:
    grouped: dict[str, list[tuple[int, str]]] = {
        name: [] for name in SPLIT_NAMES
    }
    for game_id, record in records:
        grouped[split_for_game(game_id, args.seed, split_weights)].append(
            (game_id, record)
        )

    buffers = {name: DatasetBuffers() for name in SPLIT_NAMES}
    stats = {name: SplitStats() for name in SPLIT_NAMES}
    evaluation_seen: set[tuple[int, int, int]] = set()

    for split_name in SPLIT_NAMES:
        split_records = grouped[split_name]
        print(f"replay {split_name}: {len(split_records)} games")
        for progress, (game_id, record) in enumerate(split_records, start=1):
            try:
                replay = replay_record(
                    record,
                    args.start_ply,
                    args.end_ply,
                    args.stride,
                )
            except InvalidRecordError as error:
                raise InvalidRecordError(f"game {game_id}: {error}") from error

            split_stats = stats[split_name]
            split_stats.games += 1
            split_stats.passes += replay.passes
            if replay.disc_difference > 0:
                split_stats.black_wins += 1
            elif replay.disc_difference < 0:
                split_stats.white_wins += 1
            else:
                split_stats.draws += 1

            for position in replay.positions:
                key = (position.black, position.white, position.player)
                if split_name != "train" and key in evaluation_seen:
                    split_stats.duplicate_positions_skipped += 1
                    continue
                buffers[split_name].append(
                    position,
                    game_id,
                    replay.disc_difference,
                    replay.filled_difference,
                )
                split_stats.positions += 1
                evaluation_seen.add(key)

            if progress % 1000 == 0 or progress == len(split_records):
                print(
                    f"  {split_name}: {progress}/{len(split_records)} games, "
                    f"{split_stats.positions} positions"
                )
    return buffers, stats


def write_dataset(
    output_dir: Path,
    buffers: dict[str, DatasetBuffers],
    stats: dict[str, SplitStats],
    records: list[tuple[int, str]],
    source_paths: list[Path],
    args: argparse.Namespace,
    split_weights: tuple[int, int, int],
) -> None:
    if output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {output_dir}"
            )
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    dataset_hashes = {}
    for split_name in SPLIT_NAMES:
        path = output_dir / f"{split_name}.npz"
        np.savez_compressed(path, **buffers[split_name].arrays())
        dataset_hashes[path.name] = sha256_file(path)

    source_manifest = [
        {
            "file": path.name,
            "bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        }
        for path in source_paths
    ]
    metadata = {
        "dataset_format": 1,
        "name": "nyanyan-self-play-v1",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "source": {
            "repository": SOURCE_REPOSITORY,
            "commit": SOURCE_COMMIT,
            "license": SOURCE_LICENSE,
            "files": source_manifest,
            "games": len(records),
        },
        "sampling": {
            "start_ply": args.start_ply,
            "end_ply": args.end_ply,
            "stride": args.stride,
            "coordinate_transform": "horizontal mirror into Java/server orientation",
        },
        "split": {
            "seed": args.seed,
            "weights": dict(zip(SPLIT_NAMES, split_weights)),
            "method": "SHA-256(seed:game_id), split by game",
            "evaluation_duplicate_policy": (
                "validation/test exact positions are excluded when already seen"
            ),
        },
        "labels": {
            "label_disc": "final black discs minus final white discs",
            "label_filled": (
                "label_disc with remaining empties awarded to the winner"
            ),
            "perspective": "black",
        },
        "features": {
            "patterns": {
                name: {
                    "instances": len(patterns),
                    "squares": len(patterns[0]),
                    "encoding": "base-3 empty=0 black=1 white=2",
                }
                for name, patterns in PATTERN_GROUPS.items()
            },
            "mobility": "legal moves for side to move, signed by color",
            "frontier_black": "black discs adjacent to an empty square",
            "frontier_white": "white discs adjacent to an empty square",
        },
        "stats": {name: asdict(stats[name]) for name in SPLIT_NAMES},
        "files": dataset_hashes,
    }
    metadata_path = output_dir / "metadata.json"
    metadata_path.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(metadata["stats"], ensure_ascii=False, indent=2))
    print(f"dataset written to {output_dir}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    try:
        split_weights = validate_args(args)
        if args.no_download:
            source_paths = [
                args.source_dir / f"{number:07d}.txt"
                for number in range(args.source_files)
            ]
            missing = [path for path in source_paths if not path.is_file()]
            if missing:
                raise FileNotFoundError(f"missing source file: {missing[0]}")
        else:
            source_paths = download_sources(args.source_dir, args.source_files)
        records = load_records(source_paths, args.max_games)
        print(f"loaded {len(records)} games")
        buffers, stats = build(records, args, split_weights)
        write_dataset(
            args.output_dir,
            buffers,
            stats,
            records,
            source_paths,
            args,
            split_weights,
        )
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
