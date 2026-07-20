"""Local pattern definitions for the learned evaluator."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PatternGroup:
    patterns: tuple[tuple[int, ...], ...]
    class_ids: tuple[int, ...]
    class_names: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if len(self.patterns) != len(self.class_ids):
            raise ValueError("patterns and class_ids must have equal length")
        if not self.patterns:
            raise ValueError("pattern group must not be empty")
        if self.class_names:
            expected = set(range(len(self.class_names)))
            if set(self.class_ids) != expected:
                raise ValueError("class_ids must cover every declared class")
        elif any(class_id != 0 for class_id in self.class_ids):
            raise ValueError("unclassified groups must use class id zero")

        for class_id in set(self.class_ids):
            sizes = {
                len(pattern)
                for pattern, actual_class in zip(
                    self.patterns,
                    self.class_ids,
                    strict=True,
                )
                if actual_class == class_id
            }
            if len(sizes) != 1:
                raise ValueError("one class cannot contain mixed pattern sizes")

    @property
    def instances(self) -> int:
        return len(self.patterns)

    @property
    def max_squares(self) -> int:
        return max(len(pattern) for pattern in self.patterns)

    @property
    def class_count(self) -> int:
        return len(self.class_names)

    @property
    def input_width(self) -> int:
        return self.max_squares * 2 + self.class_count

    def size_for_class(self, class_id: int) -> int:
        return len(self.patterns[self.class_ids.index(class_id)])


def _reverse(pattern: tuple[int, ...]) -> tuple[int, ...]:
    return tuple(reversed(pattern))


def _with_reversals(
    patterns: tuple[tuple[int, ...], ...],
) -> tuple[tuple[int, ...], ...]:
    return patterns + tuple(_reverse(pattern) for pattern in patterns)


_DIAGONALS = (
    (0, 9, 18, 27, 36, 45, 54, 63),
    (7, 14, 21, 28, 35, 42, 49, 56),
)

_EDGE_2X = (
    (9, 0, 1, 2, 3, 4, 5, 6, 7, 14),
    (9, 0, 8, 16, 24, 32, 40, 48, 56, 49),
    (49, 56, 57, 58, 59, 60, 61, 62, 63, 54),
    (54, 63, 55, 47, 39, 31, 23, 15, 7, 14),
)

_CORNER_TRIANGLES = (
    (0, 1, 2, 3, 8, 9, 10, 16, 17, 24),
    (0, 8, 16, 24, 1, 9, 17, 2, 10, 3),
    (7, 6, 5, 4, 15, 14, 13, 23, 22, 31),
    (7, 15, 23, 31, 6, 14, 22, 5, 13, 4),
    (63, 62, 61, 60, 55, 54, 53, 47, 46, 39),
    (63, 55, 47, 39, 62, 54, 46, 61, 53, 60),
    (56, 57, 58, 59, 48, 49, 50, 40, 41, 32),
    (56, 48, 40, 32, 57, 49, 41, 58, 50, 59),
)


def _straight_lines(distance: int) -> tuple[tuple[int, ...], ...]:
    low = distance
    high = 7 - distance
    return (
        tuple(low * 8 + x for x in range(8)),
        tuple(high * 8 + x for x in range(8)),
        tuple(y * 8 + low for y in range(8)),
        tuple(y * 8 + high for y in range(8)),
    )


_LINE_CLASSES = tuple(
    pattern
    for distance in (1, 2, 3)
    for pattern in _with_reversals(_straight_lines(distance))
)
_LINE_CLASS_IDS = tuple(
    class_id
    for class_id in range(3)
    for _ in range(8)
)

_SHORT_DIAGONAL_7 = (
    (1, 10, 19, 28, 37, 46, 55),
    (8, 17, 26, 35, 44, 53, 62),
    (6, 13, 20, 27, 34, 41, 48),
    (15, 22, 29, 36, 43, 50, 57),
)
_SHORT_DIAGONAL_6 = (
    (2, 11, 20, 29, 38, 47),
    (16, 25, 34, 43, 52, 61),
    (5, 12, 19, 26, 33, 40),
    (23, 30, 37, 44, 51, 58),
)
_SHORT_DIAGONALS = _with_reversals(
    _SHORT_DIAGONAL_7
) + _with_reversals(_SHORT_DIAGONAL_6)
_SHORT_DIAGONAL_CLASSES = (0,) * 8 + (1,) * 8

_CORNER_3X3 = (
    (0, 1, 2, 8, 9, 10, 16, 17, 18),
    (0, 8, 16, 1, 9, 17, 2, 10, 18),
    (7, 6, 5, 15, 14, 13, 23, 22, 21),
    (7, 15, 23, 6, 14, 22, 5, 13, 21),
    (63, 62, 61, 55, 54, 53, 47, 46, 45),
    (63, 55, 47, 62, 54, 46, 61, 53, 45),
    (56, 57, 58, 48, 49, 50, 40, 41, 42),
    (56, 48, 40, 57, 49, 41, 58, 50, 42),
)


PATTERN_GROUPS = {
    "diagonal": PatternGroup(_with_reversals(_DIAGONALS), (0,) * 4),
    "edge2x": PatternGroup(_with_reversals(_EDGE_2X), (0,) * 8),
    "corner": PatternGroup(_CORNER_TRIANGLES, (0,) * 8),
    "line8": PatternGroup(
        _LINE_CLASSES,
        _LINE_CLASS_IDS,
        ("line2", "line3", "line4"),
    ),
    "short_diagonal": PatternGroup(
        _SHORT_DIAGONALS,
        _SHORT_DIAGONAL_CLASSES,
        ("length7", "length6"),
    ),
    "corner3x3": PatternGroup(_CORNER_3X3, (0,) * 8),
}


def encode_pattern(own: int, opponent: int, squares: tuple[int, ...]) -> int:
    """Encode empty/own/opponent as base-3 digits 0/1/2."""
    index = 0
    for square in squares:
        mask = 1 << square
        state = 1 if own & mask else 2 if opponent & mask else 0
        index = index * 3 + state
    return index


def encode_group(
    own: int,
    opponent: int,
    group: PatternGroup,
) -> tuple[int, ...]:
    return tuple(
        encode_pattern(own, opponent, pattern)
        for pattern in group.patterns
    )
