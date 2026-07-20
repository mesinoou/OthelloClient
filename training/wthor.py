"""Reader for 8x8 WTHOR game archives used by the opening-book builder."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import struct
import zipfile

from training.othello import InvalidRecordError, wthor_move_code_to_square


HEADER_BYTES = 16
GAME_BYTES = 68
MOVE_BYTES = 60


@dataclass(frozen=True)
class WthorGame:
    archive: str
    member: str
    index: int
    black_score: int
    moves: tuple[int, ...]

    @property
    def split_key(self) -> str:
        return f"wthor:{self.archive}:{self.member}:{self.index}"


def read_wtb(data: bytes, archive: str, member: str) -> list[WthorGame]:
    if len(data) < HEADER_BYTES:
        raise InvalidRecordError(f"truncated WTHOR header: {member}")

    game_count = struct.unpack_from("<i", data, 4)[0]
    board_size = data[12]
    game_type = data[13]
    if game_count < 0:
        raise InvalidRecordError(f"invalid WTHOR game count: {member}")
    if board_size not in (0, 8):
        raise InvalidRecordError(
            f"unsupported WTHOR board size {board_size}: {member}"
        )
    if game_type != 0:
        raise InvalidRecordError(f"WTHOR member is not a game archive: {member}")

    expected_size = HEADER_BYTES + game_count * GAME_BYTES
    if len(data) != expected_size:
        raise InvalidRecordError(
            f"invalid WTHOR size for {member}: "
            f"expected={expected_size}, actual={len(data)}"
        )

    games = []
    for game_index in range(game_count):
        offset = HEADER_BYTES + game_index * GAME_BYTES
        black_score = data[offset + 6]
        if black_score > 64:
            raise InvalidRecordError(
                f"invalid WTHOR black score {black_score}: "
                f"{member} game {game_index}"
            )
        raw_moves = data[offset + 8 : offset + 8 + MOVE_BYTES]
        move_count = raw_moves.find(0)
        if move_count < 0:
            move_count = MOVE_BYTES
        try:
            moves = tuple(
                wthor_move_code_to_square(code)
                for code in raw_moves[:move_count]
            )
        except InvalidRecordError as error:
            raise InvalidRecordError(
                f"{member} game {game_index}: {error}"
            ) from error
        games.append(
            WthorGame(
                archive=archive,
                member=member,
                index=game_index,
                black_score=black_score,
                moves=moves,
            )
        )
    return games


def read_zip(path: Path) -> list[WthorGame]:
    games = []
    try:
        with zipfile.ZipFile(path) as archive:
            members = sorted(
                name
                for name in archive.namelist()
                if name.lower().endswith(".wtb")
                and not name.endswith(("/", "\\"))
            )
            if not members:
                raise InvalidRecordError(f"no WTB members in {path}")
            for member in members:
                games.extend(read_wtb(archive.read(member), path.name, member))
    except zipfile.BadZipFile as error:
        raise InvalidRecordError(f"invalid WTHOR ZIP: {path}") from error
    return games
