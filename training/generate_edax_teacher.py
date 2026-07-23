"""Score search-evaluation positions with Edax's batch solver."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


RESULT_PATTERN = re.compile(r"^\s*(\d+)\|(.*)$")
DEPTH_PATTERN = re.compile(r"^(\d+)")
SCORE_PATTERN = re.compile(r"^([+-])(\d+)$")


@dataclass(frozen=True)
class EdaxResult:
    index: int
    depth: int
    score: int
    time_ms: int
    nodes: int
    pv: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def server_position_to_obf(player: int, opponent: int) -> str:
    if player & opponent:
        raise ValueError("position boards overlap")
    cells = []
    for y in range(8):
        for edax_x in range(8):
            server_x = 7 - edax_x
            bit = 1 << (y * 8 + server_x)
            cells.append("X" if player & bit else "O" if opponent & bit else "-")
    return "".join(cells) + " X"


def elapsed_to_millis(value: str) -> int:
    parts = value.split(":")
    if len(parts) != 2:
        raise ValueError(f"invalid Edax elapsed time: {value}")
    minutes = int(parts[0])
    seconds = float(parts[1])
    return int(round((minutes * 60.0 + seconds) * 1000.0))


def parse_result_line(line: str) -> EdaxResult | None:
    match = RESULT_PATTERN.match(line)
    if match is None:
        return None
    index = int(match.group(1))
    fields = match.group(2).split()
    if len(fields) < 4:
        raise ValueError(f"invalid Edax result row: {line}")
    depth_match = DEPTH_PATTERN.match(fields[0])
    score_match = SCORE_PATTERN.match(fields[1])
    if depth_match is None or score_match is None:
        raise ValueError(f"invalid Edax depth or score: {line}")
    depth = int(depth_match.group(1))
    magnitude = int(score_match.group(2))
    score = magnitude if score_match.group(1) == "+" else -magnitude
    return EdaxResult(
        index=index,
        depth=depth,
        score=score,
        time_ms=elapsed_to_millis(fields[2]),
        nodes=int(fields[3]),
        pv=" ".join(fields[4:]),
    )


def decode_process_output(data: bytes) -> str:
    for encoding in ("utf-8", "cp932"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise ValueError("cannot decode Edax output")


def load_rows(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError("search-evaluation TSV has no header")
        required = {"black", "white", "player"}
        missing = required - set(reader.fieldnames)
        if missing:
            raise ValueError(f"input is missing columns: {sorted(missing)}")
        rows = list(reader)
        return list(reader.fieldnames), rows


def write_obf(rows: list[dict[str, str]], path: Path) -> None:
    with path.open("w", encoding="ascii", newline="\n") as stream:
        for row in rows:
            player = int(row["player"])
            black = int(row["black"], 16)
            white = int(row["white"], 16)
            own = black if player == 1 else white
            opponent = white if player == 1 else black
            stream.write(server_position_to_obf(own, opponent) + "\n")


def run_edax(
    executable: Path,
    evaluation_file: Path,
    obf_path: Path,
    level: int,
    threads: int,
    timeout_seconds: int | None,
) -> tuple[list[EdaxResult], str, float]:
    command = [
        str(executable.resolve()),
        "-solve",
        str(obf_path.resolve()),
        "-level",
        str(level),
        "-n-tasks",
        str(threads),
        "-ponder",
        "off",
        "-book-usage",
        "off",
        "-eval-file",
        str(evaluation_file.resolve()),
    ]
    started = time.perf_counter()
    completed = subprocess.run(
        command,
        cwd=executable.resolve().parent,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=timeout_seconds,
    )
    elapsed = time.perf_counter() - started
    stdout = decode_process_output(completed.stdout)
    stderr = decode_process_output(completed.stderr)
    if completed.returncode != 0:
        raise RuntimeError(
            f"Edax exited with {completed.returncode}: {stderr.strip()}"
        )
    results = [
        result
        for line in stdout.splitlines()
        if (result := parse_result_line(line)) is not None
    ]
    return results, stderr, elapsed


def write_results(
    fieldnames: list[str],
    rows: list[dict[str, str]],
    results: list[EdaxResult],
    output_path: Path,
    level: int,
) -> None:
    if len(results) != len(rows):
        raise ValueError(
            f"Edax returned {len(results)} rows for {len(rows)} positions"
        )
    expected = list(range(1, len(results) + 1))
    if [result.index for result in results] != expected:
        raise ValueError("Edax result indices are incomplete or out of order")
    extra = (
        "edax_level",
        "edax_depth",
        "edax_score",
        "edax_time_ms",
        "edax_nodes",
        "edax_pv",
    )
    with output_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=fieldnames + list(extra),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        for row, result in zip(rows, results, strict=True):
            writer.writerow(
                row
                | {
                    "edax_level": level,
                    "edax_depth": result.depth,
                    "edax_score": result.score,
                    "edax_time_ms": result.time_ms,
                    "edax_nodes": result.nodes,
                    "edax_pv": result.pv,
                }
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Attach Edax batch-solver scores to search leaves.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--edax-executable",
        type=Path,
        default=Path("benchmark/edax/wEdax-x86-64-v3.exe"),
    )
    parser.add_argument(
        "--evaluation-file",
        type=Path,
        default=Path("benchmark/edax/data/eval.dat"),
    )
    parser.add_argument("--level", type=int, default=11)
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=int, default=None)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 0 <= args.level <= 60:
        raise ValueError("Edax level must be between 0 and 60")
    if args.threads < 1:
        raise ValueError("threads must be positive")
    if args.timeout_seconds is not None and args.timeout_seconds < 1:
        raise ValueError("timeout must be positive")
    if not args.edax_executable.is_file():
        raise FileNotFoundError(args.edax_executable)
    if not args.evaluation_file.is_file():
        raise FileNotFoundError(args.evaluation_file)
    for path in (args.output, args.output.with_suffix(".obf")):
        if path.exists() and not args.overwrite:
            raise FileExistsError(f"output exists; pass --overwrite: {path}")
    args.output.parent.mkdir(parents=True, exist_ok=True)

    fieldnames, rows = load_rows(args.input)
    obf_path = args.output.with_suffix(".obf")
    write_obf(rows, obf_path)
    print(
        f"Edax teacher start: positions={len(rows)} level={args.level} "
        f"threads={args.threads}"
    )
    results, stderr, elapsed = run_edax(
        args.edax_executable,
        args.evaluation_file,
        obf_path,
        args.level,
        args.threads,
        args.timeout_seconds,
    )
    write_results(
        fieldnames,
        rows,
        results,
        args.output,
        args.level,
    )
    metadata = {
        "input": {
            "path": str(args.input),
            "sha256": sha256_file(args.input),
            "positions": len(rows),
        },
        "obf": {
            "path": str(obf_path),
            "sha256": sha256_file(obf_path),
        },
        "output": {
            "path": str(args.output),
            "sha256": sha256_file(args.output),
        },
        "edax": {
            "executable": str(args.edax_executable),
            "executable_sha256": sha256_file(args.edax_executable),
            "evaluation_file": str(args.evaluation_file),
            "evaluation_file_sha256": sha256_file(args.evaluation_file),
            "level": args.level,
            "threads": args.threads,
        },
        "elapsed_seconds": elapsed,
        "total_nodes": sum(result.nodes for result in results),
        "mean_depth": sum(result.depth for result in results) / len(results),
        "exact_positions": sum(
            result.depth
            >= 64
            - (
                int(row["black"], 16) | int(row["white"], 16)
            ).bit_count()
            for row, result in zip(rows, results, strict=True)
        ),
        "stderr": stderr.strip(),
    }
    metadata_path = args.output.with_suffix(".json")
    metadata_path.write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Edax teacher written: {args.output} "
        f"elapsed={elapsed:.1f}s nodes={metadata['total_nodes']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
