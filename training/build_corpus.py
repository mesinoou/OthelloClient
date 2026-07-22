"""Build a model-independent, sharded Othello position corpus."""

from __future__ import annotations

import argparse
from array import array
import base64
from dataclasses import dataclass
from datetime import datetime, timezone
import gzip
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
    replay_squares,
    source_coordinate_to_square,
)
from training.wthor import WthorGame, read_zip


SELF_PLAY_REPOSITORY = "Nyanyan/OthelloAI_Textbook"
SELF_PLAY_COMMIT = "ca3dbb5bd39825ea8f6c9526243e548239066873"
SELF_PLAY_LICENSE = "MIT"
SELF_PLAY_FILE_COUNT = 20
SELF_PLAY_URL = (
    "https://raw.githubusercontent.com/"
    f"{SELF_PLAY_REPOSITORY}/{SELF_PLAY_COMMIT}/evaluation/self_play"
)
WTHOR_BASE_URL = "https://www.ffothello.org/wthor/base_zip"
WTHOR_REGISTRY = Path(__file__).with_name("wthor_sources.json")

SOURCE_IDS = {"self_play": 0, "wthor": 1}
SPLIT_IDS = {"train": 0, "validation": 1, "test": 2}
SPLIT_NAMES = tuple(SPLIT_IDS)
NO_THEORETICAL_LABEL = 127


@dataclass(frozen=True)
class CorpusGame:
    source: str
    source_key: str
    moves: tuple[int, ...]
    actual_black_score: int | None = None
    theoretical_black_score: int | None = None
    theoretical_empties: int | None = None
    archive: str | None = None
    member: str | None = None


class CorpusShard:
    def __init__(self) -> None:
        self.black = array("Q")
        self.white = array("Q")
        self.player = array("b")
        self.ply = array("B")
        self.game_id = array("I")
        self.source = array("B")
        self.split = array("B")
        self.label_disc = array("b")
        self.label_filled = array("b")
        self.theoretical_disc = array("b")

    def __len__(self) -> int:
        return len(self.player)

    def append(
        self,
        position: Position,
        game_id: int,
        source_id: int,
        split_id: int,
        label_disc: int,
        label_filled: int,
        theoretical_disc: int | None,
    ) -> None:
        self.black.append(position.black)
        self.white.append(position.white)
        self.player.append(position.player)
        self.ply.append(position.ply)
        self.game_id.append(game_id)
        self.source.append(source_id)
        self.split.append(split_id)
        self.label_disc.append(label_disc)
        self.label_filled.append(label_filled)
        self.theoretical_disc.append(
            NO_THEORETICAL_LABEL
            if theoretical_disc is None
            else theoretical_disc
        )

    def arrays(self) -> dict[str, np.ndarray]:
        return {
            "black": _from_array(self.black, np.uint64),
            "white": _from_array(self.white, np.uint64),
            "player": _from_array(self.player, np.int8),
            "ply": _from_array(self.ply, np.uint8),
            "game_id": _from_array(self.game_id, np.uint32),
            "source": _from_array(self.source, np.uint8),
            "split": _from_array(self.split, np.uint8),
            "label_disc": _from_array(self.label_disc, np.int8),
            "label_filled": _from_array(self.label_filled, np.int8),
            "theoretical_disc": _from_array(
                self.theoretical_disc,
                np.int8,
            ),
        }


def _from_array(values: array, dtype: np.dtype) -> np.ndarray:
    return np.frombuffer(values, dtype=dtype).copy()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_wthor_registry(path: Path = WTHOR_REGISTRY) -> dict[str, object]:
    registry = json.loads(path.read_text(encoding="utf-8"))
    archives = registry.get("archives")
    if not isinstance(archives, list) or not archives:
        raise ValueError(f"invalid WTHOR source registry: {path}")
    expected_total = sum(int(item["games"]) for item in archives)
    if expected_total != int(registry["expected_total_games"]):
        raise ValueError("WTHOR registry game total does not match")
    filenames = [str(item["file"]) for item in archives]
    if len(filenames) != len(set(filenames)):
        raise ValueError("WTHOR registry contains duplicate filenames")
    return registry


def download_file(path: Path, url: str) -> None:
    if path.is_file() and path.stat().st_size > 0:
        return
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "mesinoou-OthelloClient-corpus-builder/1"},
    )
    print(f"download {url}")
    with urllib.request.urlopen(request, timeout=120) as response:
        content = response.read()
    if not content:
        raise IOError(f"downloaded an empty source file: {url}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def prepare_self_play_sources(
    source_dir: Path,
    file_count: int,
    no_download: bool,
) -> list[Path]:
    paths = [source_dir / f"{number:07d}.txt" for number in range(file_count)]
    if not no_download:
        for path in paths:
            download_file(path, f"{SELF_PLAY_URL}/{path.name}")
    _require_files(paths)
    return paths


def prepare_wthor_sources(
    source_dir: Path,
    registry: dict[str, object],
    no_download: bool,
) -> list[tuple[Path, dict[str, object]]]:
    result = []
    for item in registry["archives"]:
        path = source_dir / str(item["file"])
        if not no_download:
            download_file(path, f"{WTHOR_BASE_URL}/{path.name}")
        _require_files([path])
        expected_hash = item.get("sha256")
        actual_hash = sha256_file(path)
        if expected_hash is not None and actual_hash != expected_hash:
            raise InvalidRecordError(f"WTHOR SHA-256 mismatch: {path}")
        result.append((path, item))
    return result


def _require_files(paths: list[Path]) -> None:
    missing = [path for path in paths if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"missing source file: {missing[0]}")


def load_self_play_games(
    paths: list[Path],
    maximum: int | None,
) -> list[CorpusGame]:
    games = []
    for path in paths:
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(),
            start=1,
        ):
            record = line.strip().lower()
            if not record:
                continue
            if len(record) % 2:
                raise InvalidRecordError(
                    f"{path}:{line_number}: record length is odd"
                )
            moves = tuple(
                source_coordinate_to_square(record[offset : offset + 2])
                for offset in range(0, len(record), 2)
            )
            games.append(
                CorpusGame(
                    source="self_play",
                    source_key=f"self_play:{path.name}:{line_number}",
                    moves=moves,
                )
            )
            if maximum is not None and len(games) >= maximum:
                return games
    return games


def load_wthor_games(
    sources: list[tuple[Path, dict[str, object]]],
    maximum: int | None,
) -> list[CorpusGame]:
    result = []
    for path, source in sources:
        games = read_zip(path)
        expected = int(source["games"])
        if len(games) != expected:
            raise InvalidRecordError(
                f"unexpected game count in {path}: "
                f"expected={expected}, actual={len(games)}"
            )
        for game in games:
            result.append(_from_wthor_game(game))
            if maximum is not None and len(result) >= maximum:
                return result
    return result


def _from_wthor_game(game: WthorGame) -> CorpusGame:
    return CorpusGame(
        source="wthor",
        source_key=game.split_key,
        moves=game.moves,
        actual_black_score=game.black_score,
        theoretical_black_score=game.theoretical_black_score,
        theoretical_empties=game.theoretical_empties,
        archive=game.archive,
        member=game.member,
    )


def transform_bitboard(board: int, symmetry: int) -> int:
    transformed = 0
    remaining = board
    while remaining:
        bit = remaining & -remaining
        square = bit.bit_length() - 1
        x = square & 7
        y = square >> 3
        coordinates = (
            (x, y),
            (7 - y, x),
            (7 - x, 7 - y),
            (y, 7 - x),
            (7 - x, y),
            (x, 7 - y),
            (y, x),
            (7 - y, 7 - x),
        )
        tx, ty = coordinates[symmetry]
        transformed |= 1 << (ty * 8 + tx)
        remaining ^= bit
    return transformed


def canonical_position_key(position: Position) -> str:
    variants = [
        (
            transform_bitboard(position.black, symmetry),
            transform_bitboard(position.white, symmetry),
        )
        for symmetry in range(8)
    ]
    black, white = min(variants)
    payload = (
        black.to_bytes(8, "little")
        + white.to_bytes(8, "little")
        + int(position.player).to_bytes(1, "little", signed=True)
    )
    return hashlib.sha256(payload).hexdigest()[:24]


def split_for_key(
    key: str,
    seed: int,
    weights: tuple[int, int, int],
) -> str:
    payload = f"{seed}:{key}".encode("ascii")
    bucket = int.from_bytes(hashlib.sha256(payload).digest()[:8], "big")
    bucket %= sum(weights)
    if bucket < weights[0]:
        return "train"
    if bucket < weights[0] + weights[1]:
        return "validation"
    return "test"


def parse_split(value: str) -> tuple[int, int, int]:
    parts = tuple(int(item) for item in value.split(","))
    if len(parts) != 3 or any(item <= 0 for item in parts):
        raise ValueError("split must contain three positive integers")
    return parts


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a reusable corpus from public Othello games.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path(".training/sources/OthelloAI_Textbook"),
    )
    parser.add_argument(
        "--wthor-source-dir",
        type=Path,
        default=Path(".training/sources/wthor"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/corpora/othello-public-v1"),
    )
    parser.add_argument("--source-files", type=int, default=20)
    parser.add_argument("--max-games", type=int, default=None)
    parser.add_argument("--max-wthor-games", type=int, default=None)
    parser.add_argument("--exclude-self-play", action="store_true")
    parser.add_argument("--exclude-wthor", action="store_true")
    parser.add_argument("--start-ply", type=int, default=0)
    parser.add_argument("--end-ply", type=int, default=59)
    parser.add_argument("--stride", type=int, default=1)
    parser.add_argument("--opening-ply", type=int, default=12)
    parser.add_argument("--split", default="80,10,10")
    parser.add_argument("--seed", type=int, default=20260722)
    parser.add_argument("--shard-size", type=int, default=250000)
    parser.add_argument("--no-download", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> tuple[int, int, int]:
    if not 0 <= args.start_ply <= args.end_ply <= 60:
        raise ValueError("ply range must satisfy 0 <= start <= end <= 60")
    if not 0 <= args.opening_ply <= 60:
        raise ValueError("opening-ply must be between 0 and 60")
    if args.stride < 1 or args.shard_size < 1:
        raise ValueError("stride and shard-size must be positive")
    if not 1 <= args.source_files <= SELF_PLAY_FILE_COUNT:
        raise ValueError("source-files must be between 1 and 20")
    if args.max_games is not None and args.max_games < 1:
        raise ValueError("max-games must be positive")
    if args.max_wthor_games is not None and args.max_wthor_games < 1:
        raise ValueError("max-wthor-games must be positive")
    if args.exclude_self_play and args.exclude_wthor:
        raise ValueError("at least one source must be enabled")
    return parse_split(args.split)


def build_corpus(
    games: list[CorpusGame],
    source_files: dict[str, list[Path]],
    output_dir: Path,
    args: argparse.Namespace,
    split_weights: tuple[int, int, int],
) -> None:
    if output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {output_dir}"
            )
        shutil.rmtree(output_dir)
    temporary = output_dir.with_name(output_dir.name + ".tmp")
    if temporary.exists():
        shutil.rmtree(temporary)
    temporary.mkdir(parents=True)

    shard = CorpusShard()
    shard_files: list[dict[str, object]] = []
    split_games = {name: 0 for name in SPLIT_NAMES}
    split_positions = {name: 0 for name in SPLIT_NAMES}
    source_games = {name: 0 for name in SOURCE_IDS}
    source_positions = {name: 0 for name in SOURCE_IDS}
    theoretical_positions = 0
    valid_games = 0
    rejected_games = 0
    capture_start = min(args.start_ply, args.opening_ply)
    capture_end = max(args.end_ply, args.opening_ply)

    games_path = temporary / "games.jsonl.gz"
    rejected_path = temporary / "rejected-games.jsonl.gz"
    with (
        gzip.open(games_path, "wt", encoding="utf-8", newline="\n") as log,
        gzip.open(rejected_path, "wt", encoding="utf-8", newline="\n") as rejected_log,
    ):
        for input_index, game in enumerate(games):
            try:
                replay = replay_squares(
                    game.moves,
                    capture_start,
                    capture_end,
                    1,
                )
            except InvalidRecordError as error:
                if game.source != "wthor":
                    raise InvalidRecordError(
                        f"{game.source_key}: {error}"
                    ) from error
                rejected_log.write(
                    json.dumps(
                        {
                            "source": game.source,
                            "source_key": game.source_key,
                            "archive": game.archive,
                            "member": game.member,
                            "reason": str(error),
                            "moves_base64": base64.b64encode(
                                bytes(game.moves)
                            ).decode("ascii"),
                        },
                        separators=(",", ":"),
                    )
                    + "\n"
                )
                rejected_games += 1
                print(f"reject {game.source_key}: {error}")
                continue

            if game.actual_black_score is not None:
                expected = game.actual_black_score * 2 - 64
                if replay.filled_difference != expected:
                    reason = (
                        "WTHOR score mismatch: "
                        f"expected={expected}, replayed={replay.filled_difference}"
                    )
                    rejected_log.write(
                        json.dumps(
                            {
                                "source": game.source,
                                "source_key": game.source_key,
                                "archive": game.archive,
                                "member": game.member,
                                "reason": reason,
                                "moves_base64": base64.b64encode(
                                    bytes(game.moves)
                                ).decode("ascii"),
                            },
                            separators=(",", ":"),
                        )
                        + "\n"
                    )
                    rejected_games += 1
                    print(f"reject {game.source_key}: {reason}")
                    continue

            game_id = valid_games
            valid_games += 1

            by_ply = {position.ply: position for position in replay.positions}
            opening = by_ply.get(args.opening_ply)
            opening_key = (
                canonical_position_key(opening)
                if opening is not None
                else hashlib.sha256(game.source_key.encode("utf-8")).hexdigest()[:24]
            )
            split_name = split_for_key(opening_key, args.seed, split_weights)
            split_id = SPLIT_IDS[split_name]
            source_id = SOURCE_IDS[game.source]
            split_games[split_name] += 1
            source_games[game.source] += 1

            theory_ply = None
            theory_disc = None
            if (
                game.theoretical_empties is not None
                and game.theoretical_empties > 0
                and game.theoretical_black_score is not None
            ):
                theory_ply = 60 - game.theoretical_empties
                theory_disc = game.theoretical_black_score * 2 - 64

            sampled = 0
            for position in replay.positions:
                if not args.start_ply <= position.ply <= args.end_ply:
                    continue
                if (position.ply - args.start_ply) % args.stride:
                    continue
                position_theory = theory_disc if position.ply == theory_ply else None
                shard.append(
                    position,
                    game_id,
                    source_id,
                    split_id,
                    replay.disc_difference,
                    replay.filled_difference,
                    position_theory,
                )
                sampled += 1
                split_positions[split_name] += 1
                source_positions[game.source] += 1
                theoretical_positions += position_theory is not None
                if len(shard) >= args.shard_size:
                    shard_files.append(_flush_shard(temporary, shard, len(shard_files)))
                    shard = CorpusShard()

            log.write(
                json.dumps(
                    {
                        "game_id": game_id,
                        "source": game.source,
                        "source_key": game.source_key,
                        "archive": game.archive,
                        "member": game.member,
                        "moves_base64": base64.b64encode(bytes(game.moves)).decode("ascii"),
                        "move_count": len(game.moves),
                        "split": split_name,
                        "opening_group": opening_key,
                        "final_disc_difference": replay.disc_difference,
                        "final_filled_difference": replay.filled_difference,
                        "theoretical_black_score": game.theoretical_black_score,
                        "theoretical_empties": game.theoretical_empties,
                        "sampled_positions": sampled,
                    },
                    separators=(",", ":"),
                )
                + "\n"
            )
            if (input_index + 1) % 1000 == 0 or input_index + 1 == len(games):
                print(
                    f"replay {input_index + 1}/{len(games)} input games, "
                    f"accepted={valid_games}, rejected={rejected_games}, "
                    f"positions={sum(split_positions.values())}, "
                    f"shards={len(shard_files)}"
                )

    if len(shard):
        shard_files.append(_flush_shard(temporary, shard, len(shard_files)))

    source_manifest = {
        source: [
            {
                "file": path.name,
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
            for path in paths
        ]
        for source, paths in source_files.items()
    }
    metadata = {
        "corpus_format": 1,
        "name": "othello-public-v1",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "representation": {
            "positions": "raw Java/server-orientation black and white bitboards",
            "sharding": "compressed NumPy NPZ; one row per game observation",
            "games": "gzip JSON Lines with complete move sequences",
            "model_features": "not stored; materialized separately",
            "theoretical_sentinel": NO_THEORETICAL_LABEL,
        },
        "sampling": {
            "start_ply": args.start_ply,
            "end_ply": args.end_ply,
            "stride": args.stride,
        },
        "split": {
            "seed": args.seed,
            "weights": dict(zip(SPLIT_NAMES, split_weights)),
            "opening_ply": args.opening_ply,
            "method": "D4-canonical opening position hash",
        },
        "sources": {
            "self_play": {
                "id": SOURCE_IDS["self_play"],
                "repository": SELF_PLAY_REPOSITORY,
                "commit": SELF_PLAY_COMMIT,
                "license": SELF_PLAY_LICENSE,
                "games": source_games["self_play"],
                "positions": source_positions["self_play"],
                "files": source_manifest.get("self_play", []),
            },
            "wthor": {
                "id": SOURCE_IDS["wthor"],
                "registry": WTHOR_REGISTRY.name,
                "games": source_games["wthor"],
                "positions": source_positions["wthor"],
                "files": source_manifest.get("wthor", []),
                "redistribution": "raw archives are local and are not committed",
            },
        },
        "stats": {
            "input_games": len(games),
            "games": valid_games,
            "rejected_games": rejected_games,
            "positions": sum(split_positions.values()),
            "theoretical_positions": theoretical_positions,
            "split_games": split_games,
            "split_positions": split_positions,
        },
        "files": {
            games_path.name: {
                "bytes": games_path.stat().st_size,
                "sha256": sha256_file(games_path),
            },
            rejected_path.name: {
                "bytes": rejected_path.stat().st_size,
                "sha256": sha256_file(rejected_path),
            },
            **{item["file"]: item for item in shard_files},
        },
    }
    (temporary / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.rename(output_dir)
    print(json.dumps(metadata["stats"], indent=2))
    print(f"corpus written to {output_dir}")


def _flush_shard(
    output_dir: Path,
    shard: CorpusShard,
    index: int,
) -> dict[str, object]:
    path = output_dir / f"positions-{index:05d}.npz"
    np.savez_compressed(path, **shard.arrays())
    print(f"write {path.name}: {len(shard)} positions")
    return {
        "file": path.name,
        "positions": len(shard),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def main() -> int:
    args = parse_args()
    try:
        split_weights = validate_args(args)
        registry = load_wthor_registry()
        self_paths = [] if args.exclude_self_play else prepare_self_play_sources(
            args.source_dir,
            args.source_files,
            args.no_download,
        )
        wthor_sources = [] if args.exclude_wthor else prepare_wthor_sources(
            args.wthor_source_dir,
            registry,
            args.no_download,
        )
        self_games = load_self_play_games(self_paths, args.max_games)
        wthor_games = load_wthor_games(wthor_sources, args.max_wthor_games)
        games = [*self_games, *wthor_games]
        print(
            f"loaded {len(games)} games "
            f"(self_play={len(self_games)}, wthor={len(wthor_games)})"
        )
        build_corpus(
            games,
            {
                "self_play": self_paths,
                "wthor": [path for path, _ in wthor_sources],
            },
            args.output_dir,
            args,
            split_weights,
        )
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
