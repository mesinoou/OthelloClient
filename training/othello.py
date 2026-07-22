"""Bitboard replay helpers using the Java client's square convention."""

from __future__ import annotations

from dataclasses import dataclass


FULL_MASK = (1 << 64) - 1
A_FILE = 0x0101010101010101
H_FILE = 0x8080808080808080
TOP_EDGE = 0x00000000000000FF
BOTTOM_EDGE = 0xFF00000000000000
CORNERS = 0x8100000000000081

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


def wthor_move_code_to_square(move_code: int) -> int:
    """Convert a WTHOR code and mirror rows into the server orientation."""
    x = move_code % 10 - 1
    y = move_code // 10 - 1
    if not 0 <= x < 8 or not 0 <= y < 8:
        raise InvalidRecordError(f"invalid WTHOR move code: {move_code}")
    return (7 - y) * 8 + x


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


def stable_edge_discs(board: int, occupied: int) -> int:
    """Return the conservative stable-edge mask used by the Java baseline."""
    stable = 0
    for start, step in (
        (0, 1),
        (0, 8),
        (7, -1),
        (7, 8),
        (56, 1),
        (56, -8),
        (63, -1),
        (63, -8),
    ):
        stable |= _edge_run(board, start, step)

    for edge in (TOP_EDGE, BOTTOM_EDGE, A_FILE, H_FILE):
        if occupied & edge == edge:
            stable |= board & edge
    return stable


def parity_access_difference(
    own: int,
    opponent: int,
    own_moves: int,
    opponent_moves: int,
) -> int:
    remaining = ~(own | opponent) & FULL_MASK
    difference = 0
    while remaining:
        seed = remaining & -remaining
        region = _connected_region(seed, remaining)
        remaining &= ~region
        own_can_enter = bool(own_moves & region)
        opponent_can_enter = bool(opponent_moves & region)
        if own_can_enter == opponent_can_enter:
            continue
        preference = 1 if region.bit_count() & 1 else -1
        difference += preference if own_can_enter else -preference
    return difference


def _edge_run(board: int, start: int, step: int) -> int:
    run = 0
    square = start
    for _ in range(8):
        square_bit = 1 << square
        if not board & square_bit:
            break
        run |= square_bit
        square += step
    return run


def _connected_region(seed: int, empty: int) -> int:
    region = seed
    frontier = seed
    while frontier:
        frontier = _orthogonal_neighbors(frontier) & empty & ~region
        region |= frontier
    return region


def _orthogonal_neighbors(board: int) -> int:
    return (
        ((board & ~H_FILE) << 1)
        | ((board & ~A_FILE) >> 1)
        | (board << 8)
        | (board >> 8)
    ) & FULL_MASK


def replay_record(
    record: str,
    start_ply: int,
    end_ply: int,
    stride: int,
) -> ReplayResult:
    normalized = record.strip().lower()
    if len(normalized) % 2 != 0:
        raise InvalidRecordError("record length must be even")

    squares = tuple(
        source_coordinate_to_square(normalized[offset : offset + 2])
        for offset in range(0, len(normalized), 2)
    )
    return replay_squares(squares, start_ply, end_ply, stride)


def replay_squares(
    squares: tuple[int, ...],
    start_ply: int,
    end_ply: int,
    stride: int,
) -> ReplayResult:
    """Replay Java-oriented square indices and collect sampled positions."""
    if stride < 1:
        raise ValueError("stride must be positive")

    black, white = initial_position()
    player = BLACK
    passes = 0
    positions: list[Position] = []

    for ply, square in enumerate(squares):
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

        if not 0 <= square < 64:
            raise InvalidRecordError(f"invalid square {square} at ply {ply}")
        if not (moves & (1 << square)):
            raise InvalidRecordError(
                f"illegal square {square} at ply {ply}"
            )
        black, white = apply_move(black, white, player, square)
        player = -player

    own = black if player == BLACK else white
    other = white if player == BLACK else black
    if legal_moves(own, other) != 0 or legal_moves(other, own) != 0:
        raise InvalidRecordError("record ends before the game is over")

    return ReplayResult(tuple(positions), black, white, passes)
