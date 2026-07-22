"""Convert ranking-search TSV output into model-ready feature arrays."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np

from training.materialize_dataset import add_features


SPLITS = ("train", "validation", "test")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Materialize child-position features from ranking TSV."
    )
    parser.add_argument("input", type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/datasets/ranking-v1-d8-d10"),
    )
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {args.output_dir}"
            )
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)

    rows_by_split: dict[str, list[dict[str, str]]] = {
        split: [] for split in SPLITS
    }
    with args.input.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError("ranking TSV has no header")
        static_names = [
            name for name in reader.fieldnames if name.startswith("static_")
        ]
        for row in reader:
            split = row["split"]
            if split not in rows_by_split:
                raise ValueError(f"unknown split: {split}")
            rows_by_split[split].append(row)

    files = {}
    split_metadata = {}
    for split, rows in rows_by_split.items():
        if not rows:
            raise ValueError(f"ranking split is empty: {split}")
        parent_stable: dict[int, bool] = {}
        grouped: dict[int, list[dict[str, str]]] = {}
        for row in rows:
            grouped.setdefault(int(row["parent_id"]), []).append(row)
        for parent_id, parent_rows in grouped.items():
            shallow = np.asarray(
                [int(row["shallow_score"]) for row in parent_rows]
            )
            deep = np.asarray([int(row["deep_score"]) for row in parent_rows])
            parent_stable[parent_id] = bool(
                set(np.flatnonzero(shallow == shallow.max()).tolist())
                & set(np.flatnonzero(deep == deep.max()).tolist())
            )

        count = len(rows)
        data: dict[str, np.ndarray] = {
            "parent_id": np.empty(count, dtype=np.int64),
            "phase": np.empty(count, dtype=np.int8),
            "ply": np.empty(count, dtype=np.uint8),
            "black": np.empty(count, dtype=np.uint64),
            "white": np.empty(count, dtype=np.uint64),
            "player": np.empty(count, dtype=np.int8),
            "move": np.empty(count, dtype=np.uint8),
            "deep_score": np.empty(count, dtype=np.int32),
            "shallow_score": np.empty(count, dtype=np.int32),
            "teacher_stable": np.empty(count, dtype=np.bool_),
        }
        for name in static_names:
            data[name] = np.empty(count, dtype=np.int32)
        for index, row in enumerate(rows):
            parent_id = int(row["parent_id"])
            data["parent_id"][index] = parent_id
            data["phase"][index] = int(row["phase"])
            data["ply"][index] = int(row["ply"]) + 1
            data["black"][index] = int(row["child_black"], 16)
            data["white"][index] = int(row["child_white"], 16)
            data["player"][index] = int(row["player"])
            data["move"][index] = int(row["move"])
            data["deep_score"][index] = int(row["deep_score"])
            data["shallow_score"][index] = int(row["shallow_score"])
            data["teacher_stable"][index] = parent_stable[parent_id]
            for name in static_names:
                data[name][index] = int(row[name])
        featured = add_features(data, split)
        output_path = args.output_dir / f"{split}.npz"
        np.savez_compressed(output_path, **featured)
        files[output_path.name] = {
            "bytes": output_path.stat().st_size,
            "sha256": sha256_file(output_path),
        }
        split_metadata[split] = {
            "parents": len(grouped),
            "stable_parents": sum(parent_stable.values()),
            "moves": count,
            "phase_parents": {
                str(phase): len(
                    {
                        int(row["parent_id"])
                        for row in rows
                        if int(row["phase"]) == phase
                    }
                )
                for phase in range(4)
            },
        }
        print(
            f"ranking dataset {split}: parents={len(grouped)} moves={count}"
        )

    metadata = {
        "dataset_format": 1,
        "name": args.output_dir.name,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "source": {
            "path": str(args.input),
            "bytes": args.input.stat().st_size,
            "sha256": sha256_file(args.input),
        },
        "splits": split_metadata,
        "files": files,
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
