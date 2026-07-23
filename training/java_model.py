"""Read, validate, and combine Java runtime evaluation-table models."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import struct
import zlib

import numpy as np

from training.export_java_model import (
    AUXILIARY_TABLES,
    FORMAT_VERSION,
    MAGIC,
    PATTERN_LAYOUT_VERSION,
    PATTERN_TABLES,
    PHASE_COUNT,
    TABLE_COUNT,
    TERMINAL_SCORE,
)


TABLE_LENGTHS = tuple(
    3**digits for _, digits, _, _ in PATTERN_TABLES
) + tuple(length for _, length in AUXILIARY_TABLES)
TABLE_INSTANCES = tuple(
    instances for _, _, instances, _ in PATTERN_TABLES
) + (1,) * len(AUXILIARY_TABLES)


@dataclass(frozen=True)
class JavaEvaluationModel:
    phase_starts: tuple[int, ...]
    score_scale: int
    score_divisor: int
    phase_bias: tuple[int, ...]
    tables: tuple[tuple[np.ndarray, ...], ...]


def read_java_model(path: Path) -> JavaEvaluationModel:
    data = path.read_bytes()
    if len(data) < 4:
        raise ValueError(f"{path}: truncated model")
    payload = data[:-4]
    expected_crc = struct.unpack_from(">I", data, len(data) - 4)[0]
    actual_crc = zlib.crc32(payload) & 0xFFFFFFFF
    if actual_crc != expected_crc:
        raise ValueError(f"{path}: CRC32 mismatch")

    offset = 0

    def read_int() -> int:
        nonlocal offset
        if offset + 4 > len(payload):
            raise ValueError(f"{path}: truncated integer")
        value = struct.unpack_from(">i", payload, offset)[0]
        offset += 4
        return value

    if read_int() != MAGIC:
        raise ValueError(f"{path}: invalid magic")
    if read_int() != FORMAT_VERSION:
        raise ValueError(f"{path}: unsupported format version")
    if read_int() != PATTERN_LAYOUT_VERSION:
        raise ValueError(f"{path}: unsupported pattern layout")
    if read_int() != PHASE_COUNT:
        raise ValueError(f"{path}: invalid phase count")
    if read_int() != TABLE_COUNT:
        raise ValueError(f"{path}: invalid table count")
    score_scale = read_int()
    score_divisor = read_int()
    stored_bound = read_int()
    if score_scale <= 0 or score_divisor <= 0:
        raise ValueError(f"{path}: invalid score scaling")

    phase_starts = tuple(read_int() for _ in range(PHASE_COUNT))
    if any(
        right <= left
        for left, right in zip(
            phase_starts[:-1],
            phase_starts[1:],
            strict=True,
        )
    ):
        raise ValueError(f"{path}: phase starts are not ascending")

    phase_bias = []
    phases = []
    actual_bound = 0
    for phase in range(PHASE_COUNT):
        bias = read_int()
        phase_bias.append(bias)
        bound = abs(bias)
        tables = []
        for table_index, (length, instances) in enumerate(
            zip(TABLE_LENGTHS, TABLE_INSTANCES, strict=True)
        ):
            actual_length = read_int()
            if actual_length != length:
                raise ValueError(
                    f"{path}: phase {phase} table {table_index} "
                    f"length {actual_length}, expected {length}"
                )
            byte_length = length * 2
            if offset + byte_length > len(payload):
                raise ValueError(f"{path}: truncated table data")
            table = np.frombuffer(
                payload,
                dtype=">i2",
                count=length,
                offset=offset,
            ).astype(np.int16)
            offset += byte_length
            tables.append(table)
            bound += int(np.abs(table.astype(np.int32)).max()) * instances
        phases.append(tuple(tables))
        actual_bound = max(actual_bound, bound)

    if offset != len(payload):
        raise ValueError(f"{path}: unexpected trailing data")
    if actual_bound != stored_bound:
        raise ValueError(
            f"{path}: score bound mismatch {actual_bound} != {stored_bound}"
        )
    if actual_bound // score_divisor >= TERMINAL_SCORE:
        raise ValueError(f"{path}: heuristic score overlaps terminal score")
    return JavaEvaluationModel(
        phase_starts=phase_starts,
        score_scale=score_scale,
        score_divisor=score_divisor,
        phase_bias=tuple(phase_bias),
        tables=tuple(phases),
    )


def model_bound(model: JavaEvaluationModel) -> int:
    maximum = 0
    for bias, tables in zip(
        model.phase_bias,
        model.tables,
        strict=True,
    ):
        bound = abs(bias)
        for table, instances in zip(
            tables,
            TABLE_INSTANCES,
            strict=True,
        ):
            bound += int(np.abs(table.astype(np.int32)).max()) * instances
        maximum = max(maximum, bound)
    return maximum


def write_java_model(model: JavaEvaluationModel, path: Path) -> dict[str, int]:
    if len(model.phase_starts) != PHASE_COUNT:
        raise ValueError("model must have four phase starts")
    if len(model.phase_bias) != PHASE_COUNT:
        raise ValueError("model must have four phase biases")
    if len(model.tables) != PHASE_COUNT:
        raise ValueError("model must have four table phases")
    if model.score_scale <= 0 or model.score_divisor <= 0:
        raise ValueError("score scaling must be positive")
    if any(
        right <= left
        for left, right in zip(
            model.phase_starts[:-1],
            model.phase_starts[1:],
            strict=True,
        )
    ):
        raise ValueError("model phase starts are not ascending")

    maximum_bound = model_bound(model)
    if maximum_bound // model.score_divisor >= TERMINAL_SCORE:
        raise ValueError("combined heuristic score overlaps terminal score")

    payload = bytearray()
    for value in (
        MAGIC,
        FORMAT_VERSION,
        PATTERN_LAYOUT_VERSION,
        PHASE_COUNT,
        TABLE_COUNT,
        model.score_scale,
        model.score_divisor,
        maximum_bound,
        *model.phase_starts,
    ):
        payload.extend(struct.pack(">i", value))

    for phase, (bias, tables) in enumerate(
        zip(model.phase_bias, model.tables, strict=True)
    ):
        if len(tables) != TABLE_COUNT:
            raise ValueError(f"phase {phase} must contain {TABLE_COUNT} tables")
        payload.extend(struct.pack(">i", bias))
        for table_index, (table, expected) in enumerate(
            zip(tables, TABLE_LENGTHS, strict=True)
        ):
            values = np.asarray(table).reshape(-1)
            if values.size != expected:
                raise ValueError(
                    f"phase {phase} table {table_index}: "
                    f"expected {expected} entries"
                )
            minimum = int(values.min())
            maximum = int(values.max())
            if minimum < -32768 or maximum > 32767:
                raise ValueError(
                    f"phase {phase} table {table_index} exceeds int16"
                )
            payload.extend(struct.pack(">i", expected))
            payload.extend(
                values.astype(">i2", copy=False).tobytes(order="C")
            )

    checksum = zlib.crc32(payload) & 0xFFFFFFFF
    payload.extend(struct.pack(">I", checksum))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return {
        "bytes": len(payload),
        "score_scale": model.score_scale,
        "score_divisor": model.score_divisor,
        "maximum_absolute_score_bound": maximum_bound,
        "crc32": checksum,
    }


def merge_java_models(
    base_path: Path,
    correction_path: Path,
    output_path: Path,
) -> dict[str, int]:
    base = read_java_model(base_path)
    correction = read_java_model(correction_path)
    if base.phase_starts != correction.phase_starts:
        raise ValueError("base and correction phase starts differ")
    if base.score_scale != correction.score_scale:
        raise ValueError("base and correction score scales differ")
    if base.score_divisor != 1 or correction.score_divisor != 1:
        raise ValueError("model merging currently requires score divisor 1")

    phase_bias = tuple(
        left + right
        for left, right in zip(
            base.phase_bias,
            correction.phase_bias,
            strict=True,
        )
    )
    phases = []
    for base_tables, correction_tables in zip(
        base.tables,
        correction.tables,
        strict=True,
    ):
        tables = []
        for left, right in zip(
            base_tables,
            correction_tables,
            strict=True,
        ):
            combined = left.astype(np.int32) + right.astype(np.int32)
            if int(combined.min()) < -32768 or int(combined.max()) > 32767:
                raise ValueError("combined table exceeds int16")
            tables.append(combined.astype(np.int16))
        phases.append(tuple(tables))

    combined_model = JavaEvaluationModel(
        phase_starts=base.phase_starts,
        score_scale=base.score_scale,
        score_divisor=1,
        phase_bias=phase_bias,
        tables=tuple(phases),
    )
    return write_java_model(combined_model, output_path)


def scale_java_model(
    source_path: Path,
    output_path: Path,
    scale: float,
) -> dict[str, int]:
    if not 0.0 <= scale <= 1.0:
        raise ValueError("model scale must be between 0 and 1")
    source = read_java_model(source_path)
    if source.score_divisor != 1:
        raise ValueError("model scaling currently requires score divisor 1")
    scaled = JavaEvaluationModel(
        phase_starts=source.phase_starts,
        score_scale=source.score_scale,
        score_divisor=1,
        phase_bias=tuple(
            int(np.rint(value * scale)) for value in source.phase_bias
        ),
        tables=tuple(
            tuple(
                np.rint(table.astype(np.float64) * scale).astype(np.int16)
                for table in phase
            )
            for phase in source.tables
        ),
    )
    return write_java_model(scaled, output_path)
