"""Pattern definitions adapted from Nyanyan/OthelloAI_Textbook."""

from __future__ import annotations


def _reverse(pattern: tuple[int, ...]) -> tuple[int, ...]:
    return tuple(reversed(pattern))


_DIAGONALS = (
    (0, 9, 18, 27, 36, 45, 54, 63),
    (7, 14, 21, 28, 35, 42, 49, 56),
)
DIAGONAL_PATTERNS = _DIAGONALS + tuple(
    _reverse(pattern) for pattern in _DIAGONALS
)

_EDGE_2X = (
    (9, 0, 1, 2, 3, 4, 5, 6, 7, 14),
    (9, 0, 8, 16, 24, 32, 40, 48, 56, 49),
    (49, 56, 57, 58, 59, 60, 61, 62, 63, 54),
    (54, 63, 55, 47, 39, 31, 23, 15, 7, 14),
)
EDGE_2X_PATTERNS = _EDGE_2X + tuple(
    _reverse(pattern) for pattern in _EDGE_2X
)

CORNER_TRIANGLE_PATTERNS = (
    (0, 1, 2, 3, 8, 9, 10, 16, 17, 24),
    (0, 8, 16, 24, 1, 9, 17, 2, 10, 3),
    (7, 6, 5, 4, 15, 14, 13, 23, 22, 31),
    (7, 15, 23, 31, 6, 14, 22, 5, 13, 4),
    (63, 62, 61, 60, 55, 54, 53, 47, 46, 39),
    (63, 55, 47, 39, 62, 54, 46, 61, 53, 60),
    (56, 57, 58, 59, 48, 49, 50, 40, 41, 32),
    (56, 48, 40, 32, 57, 49, 41, 58, 50, 59),
)

PATTERN_GROUPS = {
    "diagonal": DIAGONAL_PATTERNS,
    "edge2x": EDGE_2X_PATTERNS,
    "corner": CORNER_TRIANGLE_PATTERNS,
}


def encode_pattern(black: int, white: int, squares: tuple[int, ...]) -> int:
    """Encode empty/black/white as base-3 digits 0/1/2."""
    index = 0
    for square in squares:
        mask = 1 << square
        state = 1 if black & mask else 2 if white & mask else 0
        index = index * 3 + state
    return index


def encode_group(
    black: int,
    white: int,
    patterns: tuple[tuple[int, ...], ...],
) -> tuple[int, ...]:
    return tuple(encode_pattern(black, white, pattern) for pattern in patterns)
