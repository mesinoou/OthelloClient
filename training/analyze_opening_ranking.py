"""Evaluate opening-book ranking weights on held-out corpus games."""

from __future__ import annotations

import argparse
import base64
import csv
from dataclasses import dataclass
import gzip
import json
import math
from pathlib import Path
from typing import Iterable

from training.build_corpus import transform_bitboard
from training.othello import BLACK, Position, replay_squares


@dataclass
class MoveCandidate:
    square: int
    games: int
    statistical_quality: int
    teacher_score: int


@dataclass
class Outcome:
    games: int = 0
    wins: int = 0
    draws: int = 0
    disc_difference_sum: int = 0

    def record(self, disc_difference: int) -> None:
        self.games += 1
        self.disc_difference_sum += disc_difference
        if disc_difference > 0:
            self.wins += 1
        elif disc_difference == 0:
            self.draws += 1

    @property
    def points(self) -> float:
        return self.wins + self.draws * 0.5


PositionKey = tuple[int, int]
Policy = dict[PositionKey, int]


def java_divide(value: int, divisor: int) -> int:
    """Match Java integer division, including negative values."""
    return math.trunc(value / divisor)


def select_candidate(
    candidates: Iterable[MoveCandidate],
    divisor: int | None,
    score_bound: int,
) -> MoveCandidate:
    def key(candidate: MoveCandidate) -> tuple[int, int, int]:
        teacher = 0
        if divisor is not None:
            bounded = max(-score_bound, min(score_bound, candidate.teacher_score))
            teacher = java_divide(bounded, divisor)
        quality = candidate.statistical_quality + teacher
        return quality, candidate.games, -candidate.square

    return max(candidates, key=key)


def canonicalize(position: Position, square: int) -> tuple[PositionKey, int]:
    own = position.black if position.player == BLACK else position.white
    other = position.white if position.player == BLACK else position.black
    variants = []
    for symmetry in range(8):
        transform_id = symmetry
        if symmetry == 4:
            transform_id = 5
        elif symmetry == 5:
            transform_id = 4
        variants.append(
            (
                transform_bitboard(own, transform_id),
                transform_bitboard(other, transform_id),
                symmetry,
            )
        )
    player, opponent, symmetry = min(variants)
    return (player, opponent), transform_square(square, symmetry)


def transform_square(square: int, symmetry: int) -> int:
    x = square & 7
    y = square >> 3
    coordinates = (
        (x, y),
        (7 - y, x),
        (7 - x, 7 - y),
        (y, 7 - x),
        (x, 7 - y),
        (7 - x, y),
        (y, x),
        (7 - y, 7 - x),
    )
    tx, ty = coordinates[symmetry]
    return ty * 8 + tx


def load_audit(path: Path) -> dict[PositionKey, list[MoveCandidate]]:
    positions: dict[PositionKey, list[MoveCandidate]] = {}
    with path.open("r", encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            key = (int(row["player"], 16), int(row["opponent"], 16))
            positions.setdefault(key, []).append(
                MoveCandidate(
                    square=int(row["square"]),
                    games=int(row["games"]),
                    statistical_quality=int(row["statistical_quality"]),
                    teacher_score=int(row["teacher_score"]),
                )
            )
    if not positions:
        raise ValueError(f"opening ranking audit is empty: {path}")
    return positions


def build_policy(
    positions: dict[PositionKey, list[MoveCandidate]],
    divisor: int | None,
    score_bound: int,
) -> Policy:
    return {
        key: select_candidate(candidates, divisor, score_bound).square
        for key, candidates in positions.items()
    }


def load_validation_outcomes(
    corpus: Path,
    archives: set[str],
    maximum_ply: int,
) -> tuple[dict[PositionKey, dict[int, Outcome]], int]:
    positions: dict[PositionKey, dict[int, Outcome]] = {}
    games = 0
    with gzip.open(corpus, "rt", encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if record["source"] != "wthor" or record["archive"] not in archives:
                continue
            moves = tuple(base64.b64decode(record["moves_base64"]))
            replay = replay_squares(moves, 0, maximum_ply - 1, 1)
            filled_difference = int(record["final_filled_difference"])
            for position, square in zip(replay.positions, moves):
                key, canonical_square = canonicalize(position, square)
                outcome = positions.setdefault(key, {}).setdefault(
                    canonical_square,
                    Outcome(),
                )
                perspective = (
                    filled_difference
                    if position.player == BLACK
                    else -filled_difference
                )
                outcome.record(perspective)
            games += 1
    if games == 0:
        raise ValueError("no validation games matched the requested archives")
    return positions, games


def wilson_lower(points: float, games: int) -> float:
    if games == 0:
        return 0.0
    probability = points / games
    z = 1.959963984540054
    z_squared = z * z
    denominator = 1.0 + z_squared / games
    center = probability + z_squared / (2.0 * games)
    margin = z * math.sqrt(
        probability * (1.0 - probability) / games
        + z_squared / (4.0 * games * games)
    )
    return (center - margin) / denominator


def evaluate_policy(
    policy: Policy,
    outcomes: dict[PositionKey, dict[int, Outcome]],
) -> dict[str, float | int]:
    covered_positions = 0
    position_visits = 0
    selected = Outcome()
    for key, moves in outcomes.items():
        square = policy.get(key)
        if square is None:
            continue
        covered_positions += 1
        position_visits += sum(outcome.games for outcome in moves.values())
        outcome = moves.get(square)
        if outcome is None:
            continue
        selected.games += outcome.games
        selected.wins += outcome.wins
        selected.draws += outcome.draws
        selected.disc_difference_sum += outcome.disc_difference_sum
    score_rate = selected.points / selected.games if selected.games else 0.0
    return {
        "covered_positions": covered_positions,
        "position_visits": position_visits,
        "selected_move_games": selected.games,
        "selected_move_coverage": (
            selected.games / position_visits if position_visits else 0.0
        ),
        "score_rate": score_rate,
        "score_rate_wilson_lower_95": wilson_lower(
            selected.points,
            selected.games,
        ),
        "mean_disc_difference": (
            selected.disc_difference_sum / selected.games
            if selected.games
            else 0.0
        ),
    }


def compare_policies(
    baseline: Policy,
    candidate: Policy,
    outcomes: dict[PositionKey, dict[int, Outcome]],
) -> dict[str, float | int]:
    changed = sum(
        baseline.get(key) != square
        for key, square in candidate.items()
        if key in baseline
    )
    comparable = 0
    baseline_score = 0.0
    candidate_score = 0.0
    baseline_margin = 0.0
    candidate_margin = 0.0
    for key, moves in outcomes.items():
        baseline_square = baseline.get(key)
        candidate_square = candidate.get(key)
        if (
            baseline_square is None
            or candidate_square is None
            or baseline_square == candidate_square
            or baseline_square not in moves
            or candidate_square not in moves
        ):
            continue
        baseline_outcome = moves[baseline_square]
        candidate_outcome = moves[candidate_square]
        comparable += 1
        baseline_score += baseline_outcome.points / baseline_outcome.games
        candidate_score += candidate_outcome.points / candidate_outcome.games
        baseline_margin += (
            baseline_outcome.disc_difference_sum / baseline_outcome.games
        )
        candidate_margin += (
            candidate_outcome.disc_difference_sum / candidate_outcome.games
        )
    return {
        "changed_training_positions": changed,
        "comparable_changed_validation_positions": comparable,
        "equal_position_score_rate_difference": (
            (candidate_score - baseline_score) / comparable
            if comparable
            else 0.0
        ),
        "equal_position_margin_difference": (
            (candidate_margin - baseline_margin) / comparable
            if comparable
            else 0.0
        ),
    }


def parse_divisors(value: str) -> list[int | None]:
    result: list[int | None] = []
    for item in value.split(","):
        item = item.strip().lower()
        if item in {"stats", "statistical", "none"}:
            result.append(None)
            continue
        divisor = int(item)
        if divisor < 1:
            raise ValueError("teacher divisors must be positive")
        result.append(divisor)
    if not result:
        raise ValueError("at least one divisor is required")
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Rank opening candidates and score them on held-out games.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("audit", type=Path)
    parser.add_argument(
        "--corpus",
        type=Path,
        default=Path(".training/corpora/othello-public-v1/games.jsonl.gz"),
    )
    parser.add_argument(
        "--archives",
        required=True,
        help="comma-separated held-out archive filenames",
    )
    parser.add_argument("--divisors", default="stats,32,16,8,4,2")
    parser.add_argument("--score-bound", type=int, default=6400)
    parser.add_argument("--maximum-ply", type=int, default=18)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    divisors = parse_divisors(args.divisors)
    archive_names = {
        item.strip()
        for item in args.archives.split(",")
        if item.strip()
    }
    positions = load_audit(args.audit)
    outcomes, validation_games = load_validation_outcomes(
        args.corpus,
        archive_names,
        args.maximum_ply,
    )
    baseline = build_policy(positions, None, args.score_bound)
    results = []
    for divisor in divisors:
        policy = build_policy(positions, divisor, args.score_bound)
        results.append(
            {
                "teacher_divisor": divisor,
                **evaluate_policy(policy, outcomes),
                **compare_policies(baseline, policy, outcomes),
            }
        )
    payload = {
        "audit": str(args.audit),
        "corpus": str(args.corpus),
        "archives": sorted(archive_names),
        "validation_games": validation_games,
        "maximum_ply": args.maximum_ply,
        "teacher_score_bound": args.score_bound,
        "results": results,
    }
    rendered = json.dumps(payload, indent=2, ensure_ascii=False)
    print(rendered)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
