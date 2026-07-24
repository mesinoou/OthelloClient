"""Convert search-evaluation samples into model-ready feature arrays."""

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


def rows_to_arrays(
    rows: list[dict[str, str]],
    split: str,
) -> dict[str, np.ndarray]:
    count = len(rows)
    data: dict[str, np.ndarray] = {
        "sample_id": np.empty(count, dtype=np.int64),
        "source_parent_id": np.empty(count, dtype=np.int64),
        "phase": np.empty(count, dtype=np.int8),
        "ply": np.empty(count, dtype=np.uint8),
        "black": np.empty(count, dtype=np.uint64),
        "white": np.empty(count, dtype=np.uint64),
        "player": np.empty(count, dtype=np.int8),
        "occurrences": np.empty(count, dtype=np.uint32),
        "static_score": np.empty(count, dtype=np.int32),
        "deep_score": np.empty(count, dtype=np.int32),
        "source_depth": np.empty(count, dtype=np.uint8),
        "teacher_depth": np.empty(count, dtype=np.uint8),
        "teacher_completed_depth": np.empty(count, dtype=np.uint8),
        "teacher_nodes": np.empty(count, dtype=np.int64),
        "teacher_exact": np.empty(count, dtype=np.bool_),
    }
    edax_fields = {
        "edax_level": np.uint8,
        "edax_depth": np.uint8,
        "edax_score": np.int16,
        "edax_time_ms": np.int32,
        "edax_nodes": np.int64,
    }
    has_edax = bool(rows) and all(
        name in rows[0] for name in edax_fields
    )
    if has_edax:
        for name, dtype in edax_fields.items():
            data[name] = np.empty(count, dtype=dtype)
    for index, row in enumerate(rows):
        data["sample_id"][index] = int(row["sample_id"])
        data["source_parent_id"][index] = int(row["source_parent_id"])
        data["phase"][index] = int(row["leaf_phase"])
        data["ply"][index] = int(row["leaf_ply"])
        data["black"][index] = int(row["black"], 16)
        data["white"][index] = int(row["white"], 16)
        data["player"][index] = int(row["player"])
        data["occurrences"][index] = int(row["occurrences"])
        data["static_score"][index] = int(row["static_score"])
        data["deep_score"][index] = int(row["deep_score"])
        data["source_depth"][index] = int(row["source_depth"])
        data["teacher_depth"][index] = int(row["teacher_depth"])
        data["teacher_completed_depth"][index] = int(
            row["teacher_completed_depth"]
        )
        data["teacher_nodes"][index] = int(row["teacher_nodes"])
        data["teacher_exact"][index] = row["teacher_exact"].lower() == "true"
        if has_edax:
            for name in edax_fields:
                data[name][index] = int(row[name])

    occupied = np.fromiter(
        (
            (int(black) | int(white)).bit_count()
            for black, white in zip(
                data["black"],
                data["white"],
                strict=True,
            )
        ),
        dtype=np.uint8,
        count=count,
    )
    if not np.array_equal(occupied, data["ply"] + 4):
        raise ValueError(f"{split}: occupied count does not match leaf ply")
    if np.any(data["player"] != 1):
        raise ValueError(f"{split}: search samples must use player=1")
    if np.any(data["occurrences"] == 0):
        raise ValueError(f"{split}: occurrence counts must be positive")
    return add_features(data, split)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Materialize search-leaf teacher samples.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("input", type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/datasets/search-evaluation-v1"),
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
            raise ValueError("search-evaluation TSV has no header")
        for row in reader:
            split = row["split"]
            if split not in rows_by_split:
                raise ValueError(f"unknown split: {split}")
            rows_by_split[split].append(row)

    files = {}
    split_metadata = {}
    seen: set[tuple[int, int]] = set()
    for split, rows in rows_by_split.items():
        if not rows:
            raise ValueError(f"search-evaluation split is empty: {split}")
        for row in rows:
            key = (int(row["black"], 16), int(row["white"], 16))
            if key in seen:
                raise ValueError(f"cross-split duplicate position in {split}")
            seen.add(key)
        featured = rows_to_arrays(rows, split)
        output_path = args.output_dir / f"{split}.npz"
        np.savez_compressed(output_path, **featured)
        files[output_path.name] = {
            "bytes": output_path.stat().st_size,
            "sha256": sha256_file(output_path),
        }
        split_metadata[split] = {
            "samples": len(rows),
            "source_parents": len(
                {int(row["source_parent_id"]) for row in rows}
            ),
            "occurrences": sum(int(row["occurrences"]) for row in rows),
            "phases": {
                str(phase): sum(
                    int(row["leaf_phase"]) == phase for row in rows
                )
                for phase in range(4)
            },
            "exact_teacher_samples": sum(
                row["teacher_exact"].lower() == "true" for row in rows
            ),
        }
        print(
            f"search evaluation {split}: samples={len(rows)} "
            f"parents={split_metadata[split]['source_parents']}"
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
