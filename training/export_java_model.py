"""Convert quantized NumPy evaluation tables to the Java runtime format."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import zlib

import numpy as np


MAGIC = 0x4F544556  # OTEV
FORMAT_VERSION = 1
PATTERN_LAYOUT_VERSION = 1
PHASE_COUNT = 4
TABLE_COUNT = 16
TERMINAL_SCORE = 100_000
MAX_HEURISTIC_SCORE = 90_000

# suffix, ternary digits, physical instances after reverse-pair merging, merge
PATTERN_TABLES = (
    ("diagonal", 8, 2, True),
    ("edge2x", 10, 4, True),
    ("corner", 10, 8, False),
    ("line8_line2", 8, 4, True),
    ("line8_line3", 8, 4, True),
    ("line8_line4", 8, 4, True),
    ("short_diagonal_length7", 7, 4, True),
    ("short_diagonal_length6", 6, 4, True),
    ("corner3x3", 9, 8, False),
)
AUXILIARY_TABLES = (
    ("mobility", 65 * 65),
    ("frontier", 65 * 65),
    ("disc_difference", 129),
    ("corner_difference", 9),
    ("corner_move_difference", 9),
    ("stable_edge_difference", 57),
    ("parity_access_difference", 65),
)


def reverse_ternary_indices(digits: int) -> np.ndarray:
    """Map each base-3 index to the index with its digits reversed."""
    count = 3**digits
    values = np.arange(count, dtype=np.int64)
    remaining = values.copy()
    reversed_values = np.zeros(count, dtype=np.int64)
    for _ in range(digits):
        reversed_values = reversed_values * 3 + remaining % 3
        remaining //= 3
    return reversed_values


def _as_int16(values: np.ndarray, key: str) -> np.ndarray:
    flattened = np.asarray(values).reshape(-1)
    if flattened.size == 0:
        raise ValueError(f"{key}: table is empty")
    minimum = int(flattened.min())
    maximum = int(flattened.max())
    if minimum < -32768 or maximum > 32767:
        raise ValueError(
            f"{key}: optimized table exceeds int16: {minimum}..{maximum}"
        )
    return flattened.astype(np.int16, copy=False)


def _int(value: int) -> bytes:
    return struct.pack(">i", value)


def export_java_model(input_path: Path, output_path: Path) -> dict[str, int]:
    """Write one validated, CRC-protected model for LearnedEvaluator."""
    with np.load(input_path, allow_pickle=False) as source:
        phase_starts = np.asarray(source["phase_starts"], dtype=np.int32)
        if phase_starts.shape != (PHASE_COUNT,):
            raise ValueError("phase_starts must contain exactly four values")
        if np.any(phase_starts[1:] <= phase_starts[:-1]):
            raise ValueError("phase_starts must be strictly ascending")
        score_scale_values = np.asarray(source["score_scale"]).reshape(-1)
        if score_scale_values.shape != (1,):
            raise ValueError("score_scale must contain one value")
        score_scale = int(score_scale_values[0])
        if score_scale <= 0:
            raise ValueError("score_scale must be positive")
        phase_bias = np.asarray(source["phase_bias"], dtype=np.int64)
        if phase_bias.shape != (PHASE_COUNT,):
            raise ValueError("phase_bias must contain exactly four values")

        optimized_phases: list[tuple[int, list[np.ndarray]]] = []
        maximum_bound = 0
        reverse_maps = {
            digits: reverse_ternary_indices(digits)
            for _, digits, _, merge in PATTERN_TABLES
            if merge
        }
        for phase in range(PHASE_COUNT):
            bias = int(phase_bias[phase])
            if not -(2**31) <= bias < 2**31:
                raise ValueError(f"phase{phase}: bias exceeds int32")
            tables: list[np.ndarray] = []
            bound = abs(bias)
            for suffix, digits, instances, merge in PATTERN_TABLES:
                key = f"phase{phase}_{suffix}"
                raw = np.asarray(source[key]).reshape(-1)
                expected = 3**digits
                if raw.size != expected:
                    raise ValueError(
                        f"{key}: expected {expected} entries, got {raw.size}"
                    )
                values = raw.astype(np.int32)
                if merge:
                    values = values + values[reverse_maps[digits]]
                table = _as_int16(values, key)
                tables.append(table)
                bound += int(np.abs(table.astype(np.int32)).max()) * instances

            for suffix, expected in AUXILIARY_TABLES:
                key = f"phase{phase}_{suffix}"
                table = _as_int16(source[key], key)
                if table.size != expected:
                    raise ValueError(
                        f"{key}: expected {expected} entries, got {table.size}"
                    )
                tables.append(table)
                bound += int(np.abs(table.astype(np.int32)).max())
            if len(tables) != TABLE_COUNT:
                raise AssertionError("Java table layout count is inconsistent")
            maximum_bound = max(maximum_bound, bound)
            optimized_phases.append((bias, tables))

    score_divisor = max(
        1,
        (maximum_bound + MAX_HEURISTIC_SCORE - 1) // MAX_HEURISTIC_SCORE,
    )
    payload = bytearray()
    for value in (
        MAGIC,
        FORMAT_VERSION,
        PATTERN_LAYOUT_VERSION,
        PHASE_COUNT,
        TABLE_COUNT,
        score_scale,
        score_divisor,
        maximum_bound,
    ):
        payload.extend(_int(value))
    for value in phase_starts:
        payload.extend(_int(int(value)))
    for bias, tables in optimized_phases:
        payload.extend(_int(bias))
        for table in tables:
            payload.extend(_int(int(table.size)))
            payload.extend(table.astype(">i2", copy=False).tobytes(order="C"))

    checksum = zlib.crc32(payload) & 0xFFFFFFFF
    payload.extend(struct.pack(">I", checksum))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(payload)
    return {
        "format_version": FORMAT_VERSION,
        "pattern_layout_version": PATTERN_LAYOUT_VERSION,
        "bytes": len(payload),
        "score_scale": score_scale,
        "score_divisor": score_divisor,
        "maximum_absolute_score_bound": maximum_bound,
        "crc32": checksum,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert evaluation-tables.npz for the Java client.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--input",
        type=Path,
        required=True,
        help="quantized evaluation-tables.npz",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="output .bin; defaults beside the input archive",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace an existing output file",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output = args.output or args.input.with_name("evaluation-tables.bin")
    try:
        if output.exists() and not args.overwrite:
            raise FileExistsError(f"output exists; pass --overwrite: {output}")
        result = export_java_model(args.input, output)
        print(json.dumps(result, indent=2))
        print(f"Java model written to {output}")
    except (KeyError, OSError, ValueError) as error:
        print(f"error: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
