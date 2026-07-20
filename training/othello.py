"""Bitboard replay helpers using the Java client's square convention."""

from __future__ import annotations

from dataclasses import dataclass


FULL_MASK = (1 << 64) - 1
A_FILE = 0x0101010101010101
H_FILE = 0x8080808080808080

BLACK = 1
WHITE = -1

_DIRECTIONS = (
    (-1, -1),
    (0, -1),
    (1, -1),
    (-1, 0),
    (1, 0),
    (-1, 1),
    (0, 1),
    (1, 1),
)


class InvalidRecordError(ValueError):
    """Raised when a self-play record contains an illegal move."""


@dataclass(frozen=True)
class Position:
    black: int
    white: int
    player: int
    ply: int


@dataclass(frozen=True)
class ReplayResult:
    positions: tuple[Position, ...]
    black: int
    white: int
    passes: int

    @property
    def disc_difference(self) -> int:
        return self.black.bit_count() - self.white.bit_count()

    @property
    def filled_difference(self) -> int:
        difference = self.disc_difference
        empties = 64 - (self.black | self.white).bit_count()
        if difference > 0:
            return difference + empties
        if difference < 0:
            return difference - empties
        return 0


def initial_position() -> tuple[int, int]:
    """Return the server/Java initial position, not Edax's orientation."""
    black = bit(3, 3) | bit(4, 4)
    white = bit(4, 3) | bit(3, 4)
    return black, white


def bit(x: int, y: int) -> int:
    return 1 << (y * 8 + x)


def shift(board: int, dx: int, dy: int) -> int:
    if dx > 0:
        board &= ~H_FILE
        board <<= 1
    elif dx < 0:
        board &= ~A_FILE
        board >>= 1

    if dy > 0:
        board <<= 8
    elif dy < 0:
        board >>= 8
    return board & FULL_MASK


def legal_moves(player: int, opponent: int) -> int:
    empty = ~(player | opponent) & FULL_MASK
    moves = 0
    for dx, dy in _DIRECTIONS:
        run = shift(player, dx, dy) & opponent
        for _ in range(5):
            run |= shift(run, dx, dy) & opponent
        moves |= shift(run, dx, dy) & empty
    return moves


def flips(player: int, opponent: int, move: int) -> int:
    if move == 0 or move & (move - 1):
        return 0
    captured = 0
    for dx, dy in _DIRECTIONS:
        run = shift(move, dx, dy) & opponent
        line = run
        while run:
            next_square = shift(run, dx, dy)
            if next_square & player:
                captured |= line
                break
            run = next_square & opponent
            line |= run
    return captured


def apply_move(
    black: int,
    white: int,
    player: int,
    square: int,
) -> tuple[int, int]:
    move = 1 << square
    if player == BLACK:
        captured = flips(black, white, move)
        if captured == 0:
            raise InvalidRecordError(f"illegal black move at square {square}")
        return black | move | captured, white & ~captured
    if player == WHITE:
        captured = flips(white, black, move)
        if captured == 0:
            raise InvalidRecordError(f"illegal white move at square {square}")
        return black & ~captured, white | move | captured
    raise ValueError("player must be BLACK or WHITE")


def source_coordinate_to_square(coordinate: str) -> int:
    """Mirror textbook/Edax coordinates into the Java server orientation."""
    if len(coordinate) != 2:
        raise InvalidRecordError(f"invalid coordinate: {coordinate!r}")
    source_x = ord(coordinate[0].lower()) - ord("a")
    y = ord(coordinate[1]) - ord("1")
    if not 0 <= source_x < 8 or not 0 <= y < 8:
        raise InvalidRecordError(f"invalid coordinate: {coordinate!r}")
    return y * 8 + (7 - source_x)


def neighbors(board: int) -> int:
    result = 0
    for dx, dy in _DIRECTIONS:
        result |= shift(board, dx, dy)
    return result


def frontier_counts(black: int, white: int) -> tuple[int, int]:
    empty = ~(black | white) & FULL_MASK
    adjacent_to_empty = neighbors(empty)
    return (
        (black & adjacent_to_empty).bit_count(),
        (white & adjacent_to_empty).bit_count(),
    )


def replay_record(
    record: str,
    start_ply: int,
    end_ply: int,
    stride: int,
) -> ReplayResult:
    normalized = record.strip().lower()
    if len(normalized) % 2 != 0:
        raise InvalidRecordError("record length must be even")
    if stride < 1:
        raise ValueError("stride must be positive")

    black, white = initial_position()
    player = BLACK
    passes = 0
    positions: list[Position] = []

    for offset in range(0, len(normalized), 2):
        ply = offset // 2
        own = black if player == BLACK else white
        other = white if player == BLACK else black
        moves = legal_moves(own, other)
        if moves == 0:
            player = -player
            passes += 1
            own, other = other, own
            moves = legal_moves(own, other)
            if moves == 0:
                raise InvalidRecordError(
                    f"record continues after game over at ply {ply}"
                )

        if start_ply <= ply <= end_ply and (
            (ply - start_ply) % stride == 0
        ):
            positions.append(Position(black, white, player, ply))

        coordinate = normalized[offset : offset + 2]
        square = source_coordinate_to_square(coordinate)
        if not (moves & (1 << square)):
            raise InvalidRecordError(
                f"illegal move {coordinate} at ply {ply}"
            )
        black, white = apply_move(black, white, player, square)
        player = -player

    own = black if player == BLACK else white
    other = white if player == BLACK else black
    if legal_moves(own, other) != 0 or legal_moves(other, own) != 0:
        raise InvalidRecordError("record ends before the game is over")

    return ReplayResult(tuple(positions), black, white, passes)
