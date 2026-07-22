"""Verify a model-independent Othello corpus and its source manifest."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sys

import numpy as np

from training.build_corpus import NO_THEORETICAL_LABEL, SOURCE_IDS, SPLIT_IDS


REQUIRED_ARRAYS = {
    "black",
    "white",
    "player",
    "ply",
    "game_id",
    "source",
    "split",
    "label_disc",
    "label_filled",
    "theoretical_disc",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify corpus hashes and row invariants.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--corpus-dir",
        type=Path,
        default=Path(".training/corpora/othello-public-v1"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        metadata_path = args.corpus_dir / "metadata.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        expected_files = metadata["files"]
        total_rows = 0
        theory_rows = 0
        split_rows = {name: 0 for name in SPLIT_IDS}
        source_rows = {name: 0 for name in SOURCE_IDS}
        game_ids = set()

        games_path = args.corpus_dir / "games.jsonl.gz"
        game_info = expected_files[games_path.name]
        if sha256_file(games_path) != game_info["sha256"]:
            raise ValueError("games.jsonl.gz SHA-256 mismatch")
        with gzip.open(games_path, "rt", encoding="utf-8") as stream:
            game_count = 0
            for expected_id, line in enumerate(stream):
                game = json.loads(line)
                if int(game["game_id"]) != expected_id:
                    raise ValueError("game IDs are not contiguous")
                if game["split"] not in SPLIT_IDS:
                    raise ValueError("invalid game split")
                game_count += 1

        rejected_path = args.corpus_dir / "rejected-games.jsonl.gz"
        rejected_info = expected_files[rejected_path.name]
        if sha256_file(rejected_path) != rejected_info["sha256"]:
            raise ValueError("rejected-games.jsonl.gz SHA-256 mismatch")
        with gzip.open(rejected_path, "rt", encoding="utf-8") as stream:
            rejected_count = sum(1 for _ in stream)

        shard_names = sorted(
            name for name in expected_files if name.startswith("positions-")
        )
        for shard_name in shard_names:
            path = args.corpus_dir / shard_name
            info = expected_files[shard_name]
            if sha256_file(path) != info["sha256"]:
                raise ValueError(f"SHA-256 mismatch: {path}")
            with np.load(path, allow_pickle=False) as archive:
                missing = REQUIRED_ARRAYS - set(archive.files)
                if missing:
                    raise ValueError(f"{path}: missing arrays {sorted(missing)}")
                data = {name: archive[name] for name in archive.files}
            count = len(data["player"])
            if count != int(info["positions"]):
                raise ValueError(f"{path}: row count mismatch")
            if any(len(values) != count for values in data.values()):
                raise ValueError(f"{path}: array lengths differ")
            if np.any(data["black"] & data["white"]):
                raise ValueError(f"{path}: overlapping bitboards")
            if not np.all(np.isin(data["player"], (-1, 1))):
                raise ValueError(f"{path}: invalid player")
            occupied = np.fromiter(
                (int(value).bit_count() for value in data["black"] | data["white"]),
                dtype=np.uint8,
                count=count,
            )
            if not np.array_equal(occupied, data["ply"] + 4):
                raise ValueError(f"{path}: ply does not match occupied count")
            if not np.all(np.isin(data["source"], tuple(SOURCE_IDS.values()))):
                raise ValueError(f"{path}: invalid source")
            if not np.all(np.isin(data["split"], tuple(SPLIT_IDS.values()))):
                raise ValueError(f"{path}: invalid split")
            valid_theory = data["theoretical_disc"] != NO_THEORETICAL_LABEL
            if np.any(np.abs(data["theoretical_disc"][valid_theory]) > 64):
                raise ValueError(f"{path}: theoretical label out of range")
            if np.any(data["game_id"] >= game_count):
                raise ValueError(f"{path}: game ID out of range")

            total_rows += count
            theory_rows += int(np.count_nonzero(valid_theory))
            game_ids.update(int(value) for value in np.unique(data["game_id"]))
            for name, identifier in SPLIT_IDS.items():
                split_rows[name] += int(np.count_nonzero(data["split"] == identifier))
            for name, identifier in SOURCE_IDS.items():
                source_rows[name] += int(np.count_nonzero(data["source"] == identifier))

        stats = metadata["stats"]
        if total_rows != int(stats["positions"]):
            raise ValueError("metadata position count differs")
        if theory_rows != int(stats["theoretical_positions"]):
            raise ValueError("metadata theoretical count differs")
        if split_rows != stats["split_positions"]:
            raise ValueError("metadata split counts differ")
        if len(game_ids) != game_count or game_count != int(stats["games"]):
            raise ValueError("not every game is represented in the corpus")
        if rejected_count != int(stats["rejected_games"]):
            raise ValueError("metadata rejected game count differs")
        if game_count + rejected_count != int(stats["input_games"]):
            raise ValueError("accepted and rejected game totals differ")
        expected_sources = {
            name: int(source["positions"])
            for name, source in metadata["sources"].items()
        }
        if source_rows != expected_sources:
            raise ValueError("metadata source counts differ")

        report = {
            "games": game_count,
            "rejected_games": rejected_count,
            "positions": total_rows,
            "theoretical_positions": theory_rows,
            "splits": split_rows,
            "sources": source_rows,
            "shards": len(shard_names),
        }
        print(json.dumps(report, indent=2))
        print("corpus verification: PASS")
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
