"""Prepare and summarize Edax regret analysis for match traces."""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re
from statistics import mean
from typing import Iterable

from training.othello import apply_move, legal_moves


PHASE_BOUNDARIES = (30, 40, 50)
MAJOR_REGRET = 4
MATCH_GAME_PATTERN = re.compile(
    r"^game=\d+/\d+ opening=(\d+) learned=(black|white) "
    r"result=(WIN|DRAW|LOSS) margin=([+-]?\d+)"
)
OUTCOME_SCORE = {"WIN": 1.0, "DRAW": 0.5, "LOSS": 0.0}


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError(f"{path}: TSV has no header")
        return list(reader)


def phase_for_ply(ply: int) -> int:
    for phase, boundary in enumerate(PHASE_BOUNDARIES):
        if ply < boundary:
            return phase
    return len(PHASE_BOUNDARIES)


def color_value(name: str) -> int:
    if name == "black":
        return 1
    if name == "white":
        return -1
    raise ValueError(f"invalid color: {name}")


def prepare_rows(
    trace_rows: Iterable[dict[str, str]],
    learned_color: str,
) -> list[dict[str, str]]:
    prepared: list[dict[str, str]] = []
    for row in trace_rows:
        if row["learned_color"] != learned_color:
            continue
        if row["actor"] != "learned":
            continue
        player = color_value(row["player"])
        black = int(row["black"], 16)
        white = int(row["white"], 16)
        own = black if player == 1 else white
        opponent = white if player == 1 else black
        moves = legal_moves(own, opponent)
        chosen_move = int(row["move"])
        if not moves & (1 << chosen_move):
            raise ValueError(
                f"trace contains illegal chosen move: "
                f"game={row['game']} ply={row['ply']} move={chosen_move}"
            )
        decision_id = f'{row["game"]}:{row["ply"]}'
        common = {
            "decision_id": decision_id,
            "game": row["game"],
            "opening": row["opening"],
            "learned_color": row["learned_color"],
            "outcome": row["outcome"],
            "margin": row["margin"],
            "ply": row["ply"],
            "chosen_move": row["move"],
            "source": row["source"],
            "search_score": row["search_score"],
            "completed_depth": row["completed_depth"],
            "elapsed_ms": row["elapsed_ms"],
            "timed_out": row["timed_out"],
            "wld_search": row["wld_search"],
            "wld_solution": row["wld_solution"],
        }
        remaining = moves
        while remaining:
            move = remaining & -remaining
            remaining ^= move
            square = move.bit_length() - 1
            child_black, child_white = apply_move(
                black,
                white,
                player,
                square,
            )
            selected = square == chosen_move
            if selected and (
                child_black != int(row["child_black"], 16)
                or child_white != int(row["child_white"], 16)
            ):
                raise ValueError(
                    f"trace child mismatch: game={row['game']} ply={row['ply']}"
                )
            prepared.append(
                common
                | {
                    "candidate_move": str(square),
                    "selected": str(selected).lower(),
                    "black": f"{child_black:016x}",
                    "white": f"{child_white:016x}",
                    "player": str(-player),
                }
            )
    return prepared


def write_tsv(rows: list[dict[str, str]], path: Path) -> None:
    if not rows:
        raise ValueError("no rows selected from trace")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=list(rows[0]),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(rows)


def prepare_opening_rows(
    trace_rows: Iterable[dict[str, str]],
) -> list[dict[str, str]]:
    first_by_game: dict[int, dict[str, str]] = {}
    for row in trace_rows:
        game = int(row["game"])
        if game not in first_by_game or int(row["ply"]) < int(
            first_by_game[game]["ply"]
        ):
            first_by_game[game] = row
    by_opening: dict[int, dict[str, str]] = {}
    for row in first_by_game.values():
        opening = int(row["opening"])
        existing = by_opening.get(opening)
        if existing is not None and (
            existing["black"] != row["black"]
            or existing["white"] != row["white"]
            or existing["player"] != row["player"]
        ):
            raise ValueError(f"paired opening position mismatch: {opening}")
        by_opening[opening] = row
    return [
        {
            "opening": str(opening),
            "black": row["black"],
            "white": row["white"],
            "player": str(color_value(row["player"])),
        }
        for opening, row in sorted(by_opening.items())
    ]


def read_match_games(path: Path) -> dict[int, dict[str, tuple[float, int]]]:
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("cp932")
    games: dict[int, dict[str, tuple[float, int]]] = defaultdict(dict)
    for line in text.splitlines():
        match = MATCH_GAME_PATTERN.match(line)
        if match is None:
            continue
        opening = int(match.group(1))
        color = match.group(2)
        games[opening][color] = (
            OUTCOME_SCORE[match.group(3)],
            int(match.group(4)),
        )
    if not games:
        raise ValueError(f"{path}: no match games found")
    if any(set(colors) != {"black", "white"} for colors in games.values()):
        raise ValueError(f"{path}: every opening must contain both colors")
    return games


def correlation(xs: list[float], ys: list[float]) -> float | None:
    if len(xs) < 2:
        return None
    x_mean = mean(xs)
    y_mean = mean(ys)
    covariance = sum(
        (x - x_mean) * (y - y_mean) for x, y in zip(xs, ys, strict=True)
    )
    x_variance = sum((x - x_mean) ** 2 for x in xs)
    y_variance = sum((y - y_mean) ** 2 for y in ys)
    denominator = math.sqrt(x_variance * y_variance)
    return covariance / denominator if denominator else None


def opening_group_summary(
    rows: list[dict[str, object]],
) -> dict[str, object]:
    if not rows:
        return {
            "openings": 0,
            "black_score_rate": 0.0,
            "white_score_rate": 0.0,
            "black_mean_margin": 0.0,
            "white_mean_margin": 0.0,
            "paired_score_rate": 0.0,
            "paired_mean_margin": 0.0,
        }
    return {
        "openings": len(rows),
        "black_score_rate": mean(float(row["black_result"]) for row in rows),
        "white_score_rate": mean(float(row["white_result"]) for row in rows),
        "black_mean_margin": mean(
            int(row["black_margin"]) for row in rows
        ),
        "white_mean_margin": mean(
            int(row["white_margin"]) for row in rows
        ),
        "paired_score_rate": mean(
            (
                float(row["black_result"])
                + float(row["white_result"])
            )
            / 2.0
            for row in rows
        ),
        "paired_mean_margin": mean(
            (
                int(row["black_margin"])
                + int(row["white_margin"])
            )
            / 2.0
            for row in rows
        ),
    }


def summarize_opening_bias(
    games: dict[int, dict[str, tuple[float, int]]],
    scored_rows: Iterable[dict[str, str]],
) -> dict[str, object]:
    scored_list = list(scored_rows)
    rows = []
    for scored in scored_list:
        opening = int(scored["opening"])
        if opening not in games:
            raise ValueError(f"scored opening absent from match: {opening}")
        player = int(scored["player"])
        black_score = int(scored["edax_score"]) * player
        black_result, black_margin = games[opening]["black"]
        white_result, white_margin = games[opening]["white"]
        rows.append(
            {
                "opening": opening,
                "edax_black_score": black_score,
                "black_result": black_result,
                "black_margin": black_margin,
                "white_result": white_result,
                "white_margin": white_margin,
            }
        )
    if len(rows) != len(games):
        raise ValueError("scored opening count differs from match")

    bins = {
        "black_advantage": [
            row for row in rows if int(row["edax_black_score"]) >= 4
        ],
        "balanced": [
            row for row in rows if abs(int(row["edax_black_score"])) < 4
        ],
        "white_advantage": [
            row for row in rows if int(row["edax_black_score"]) <= -4
        ],
    }
    scores = [float(row["edax_black_score"]) for row in rows]
    return {
        "openings": len(rows),
        "edax_level": int(scored_list[0].get("edax_level", 0))
            if scored_list
            else None,
        "edax_black_score": {
            "mean": mean(scores),
            "minimum": min(scores),
            "maximum": max(scores),
        },
        "all": opening_group_summary(rows),
        "by_initial_evaluation": {
            name: opening_group_summary(group)
            for name, group in bins.items()
        },
        "correlations": {
            "initial_score_vs_black_margin": correlation(
                scores,
                [float(row["black_margin"]) for row in rows],
            ),
            "initial_score_vs_white_margin": correlation(
                scores,
                [float(row["white_margin"]) for row in rows],
            ),
        },
        "details": rows,
    }


def decision_rows(
    scored_rows: Iterable[dict[str, str]],
) -> list[dict[str, object]]:
    grouped: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in scored_rows:
        grouped[row["decision_id"]].append(row)

    decisions: list[dict[str, object]] = []
    for decision_id, candidates in grouped.items():
        selected = [row for row in candidates if row["selected"] == "true"]
        if len(selected) != 1:
            raise ValueError(
                f"decision must contain one selected move: {decision_id}"
            )
        chosen = selected[0]
        values = [-int(row["edax_score"]) for row in candidates]
        best_index = max(range(len(candidates)), key=values.__getitem__)
        best = candidates[best_index]
        best_score = values[best_index]
        chosen_score = -int(chosen["edax_score"])
        decisions.append(
            {
                "decision_id": decision_id,
                "game": int(chosen["game"]),
                "opening": int(chosen["opening"]),
                "outcome": chosen["outcome"],
                "margin": int(chosen["margin"]),
                "ply": int(chosen["ply"]),
                "phase": phase_for_ply(int(chosen["ply"])),
                "source": chosen["source"],
                "chosen_move": int(chosen["chosen_move"]),
                "best_move": int(best["candidate_move"]),
                "legal_moves": len(candidates),
                "best_score": best_score,
                "chosen_score": chosen_score,
                "regret": best_score - chosen_score,
                "edax_depth": int(chosen["edax_depth"]),
                "edax_time_ms": sum(
                    int(row["edax_time_ms"]) for row in candidates
                ),
                "edax_pv": chosen["edax_pv"],
                "completed_depth": int(chosen["completed_depth"])
                    if chosen["completed_depth"]
                    else None,
                "elapsed_ms": float(chosen["elapsed_ms"]),
                "timed_out": chosen["timed_out"] == "true",
                "wld_search": chosen["wld_search"] == "true",
                "wld_solution": chosen["wld_solution"] == "true",
            }
        )
    decisions.sort(key=lambda row: (int(row["game"]), int(row["ply"])))
    return decisions


def basic_game_summary(
    trace_rows: Iterable[dict[str, str]],
) -> dict[str, object]:
    games: dict[int, dict[str, str]] = {}
    learned_moves: Counter[int] = Counter()
    book_moves: Counter[int] = Counter()
    first_search_ply: dict[int, int] = {}
    for row in trace_rows:
        game = int(row["game"])
        games.setdefault(game, row)
        if row["actor"] != "learned":
            continue
        learned_moves[game] += 1
        if row["source"] == "book":
            book_moves[game] += 1
        elif row["source"] == "search":
            first_search_ply.setdefault(game, int(row["ply"]))

    by_color: dict[str, dict[str, object]] = {}
    for color in ("black", "white"):
        selected = [row for row in games.values() if row["learned_color"] == color]
        outcomes = Counter(row["outcome"] for row in selected)
        by_color[color] = {
            "games": len(selected),
            "wins": outcomes["WIN"],
            "draws": outcomes["DRAW"],
            "losses": outcomes["LOSS"],
            "score_rate": (
                outcomes["WIN"] + outcomes["DRAW"] * 0.5
            ) / len(selected)
            if selected
            else 0.0,
            "mean_margin": mean(int(row["margin"]) for row in selected)
            if selected
            else 0.0,
        }

    game_details = []
    for game, row in sorted(games.items()):
        game_details.append(
            {
                "game": game,
                "opening": int(row["opening"]),
                "learned_color": row["learned_color"],
                "outcome": row["outcome"],
                "margin": int(row["margin"]),
                "learned_moves": learned_moves[game],
                "book_moves": book_moves[game],
                "first_search_ply": first_search_ply.get(game),
            }
        )
    return {"by_color": by_color, "games": game_details}


def metric_summary(
    rows: list[dict[str, object]],
) -> dict[str, object]:
    if not rows:
        return {
            "decisions": 0,
            "mean_regret": 0.0,
            "major_regrets": 0,
            "major_regret_rate": 0.0,
            "sign_flips": 0,
            "outcome_downgrades": 0,
            "outcome_downgrade_rate": 0.0,
            "win_to_nonwin": 0,
        }
    major = [row for row in rows if int(row["regret"]) >= MAJOR_REGRET]
    sign_flips = [
        row
        for row in rows
        if int(row["best_score"]) >= 0 and int(row["chosen_score"]) < 0
    ]
    outcome_downgrades = [
        row
        for row in rows
        if score_class(int(row["best_score"]))
        > score_class(int(row["chosen_score"]))
    ]
    win_to_nonwin = [
        row
        for row in rows
        if int(row["best_score"]) > 0 and int(row["chosen_score"]) <= 0
    ]
    searches = [row for row in rows if row["source"] == "search"]
    completed_depths = [
        int(row["completed_depth"])
        for row in searches
        if row["completed_depth"] is not None
    ]
    return {
        "decisions": len(rows),
        "mean_regret": mean(int(row["regret"]) for row in rows),
        "major_regrets": len(major),
        "major_regret_rate": len(major) / len(rows),
        "sign_flips": len(sign_flips),
        "outcome_downgrades": len(outcome_downgrades),
        "outcome_downgrade_rate": len(outcome_downgrades) / len(rows),
        "win_to_nonwin": len(win_to_nonwin),
        "mean_edax_time_ms": mean(int(row["edax_time_ms"]) for row in rows),
        "mean_completed_depth": mean(completed_depths)
            if completed_depths
            else None,
        "mean_search_elapsed_ms": mean(
            float(row["elapsed_ms"]) for row in searches
        )
        if searches
        else None,
        "timed_out_rate": mean(
            bool(row["timed_out"]) for row in searches
        )
        if searches
        else None,
        "wld_attempts": sum(bool(row["wld_search"]) for row in searches),
        "wld_solutions": sum(bool(row["wld_solution"]) for row in searches),
    }


def score_class(score: int) -> int:
    return 1 if score > 0 else -1 if score < 0 else 0


def summarize(
    trace_rows: list[dict[str, str]],
    scored_rows: list[dict[str, str]],
) -> dict[str, object]:
    decisions = decision_rows(scored_rows)
    games = basic_game_summary(trace_rows)

    by_outcome = {
        outcome: metric_summary(
            [row for row in decisions if row["outcome"] == outcome]
        )
        for outcome in ("WIN", "DRAW", "LOSS")
    }
    by_phase = {
        str(phase): metric_summary(
            [row for row in decisions if row["phase"] == phase]
        )
        for phase in range(4)
    }
    by_source = {
        source: metric_summary(
            [row for row in decisions if row["source"] == source]
        )
        for source in ("book", "search")
    }
    by_outcome_phase = {
        outcome: {
            str(phase): metric_summary(
                [
                    row
                    for row in decisions
                    if row["outcome"] == outcome and row["phase"] == phase
                ]
            )
            for phase in range(4)
        }
        for outcome in ("WIN", "DRAW", "LOSS")
    }
    by_outcome_source = {
        outcome: {
            source: metric_summary(
                [
                    row
                    for row in decisions
                    if row["outcome"] == outcome and row["source"] == source
                ]
            )
            for source in ("book", "search")
        }
        for outcome in ("WIN", "DRAW", "LOSS")
    }
    white_games = [
        row
        for row in games["games"]
        if row["learned_color"] == "white"
    ]
    opening_usage = {}
    for outcome in ("WIN", "DRAW", "LOSS"):
        selected = [row for row in white_games if row["outcome"] == outcome]
        search_plies = [
            int(row["first_search_ply"])
            for row in selected
            if row["first_search_ply"] is not None
        ]
        opening_usage[outcome] = {
            "games": len(selected),
            "mean_book_moves": mean(
                int(row["book_moves"]) for row in selected
            )
            if selected
            else 0.0,
            "mean_first_search_ply": mean(search_plies)
            if search_plies
            else None,
        }

    loss_rows = [row for row in decisions if row["outcome"] == "LOSS"]
    loss_groups: dict[int, list[dict[str, object]]] = defaultdict(list)
    for row in loss_rows:
        loss_groups[int(row["game"])].append(row)

    first_major_phase = Counter()
    first_sign_flip_phase = Counter()
    first_outcome_downgrade_phase = Counter()
    first_outcome_downgrade_source = Counter()
    non_initial_first_major_phase = Counter()
    loss_details = []
    for game, rows in sorted(loss_groups.items()):
        rows.sort(key=lambda row: int(row["ply"]))
        first = rows[0]
        first_major = next(
            (row for row in rows if int(row["regret"]) >= MAJOR_REGRET),
            None,
        )
        first_sign_flip = next(
            (
                row
                for row in rows
                if int(row["best_score"]) >= 0
                and int(row["chosen_score"]) < 0
            ),
            None,
        )
        first_outcome_downgrade = next(
            (
                row
                for row in rows
                if score_class(int(row["best_score"]))
                > score_class(int(row["chosen_score"]))
            ),
            None,
        )
        if first_major is not None:
            first_major_phase[int(first_major["phase"])] += 1
        if first_sign_flip is not None:
            first_sign_flip_phase[int(first_sign_flip["phase"])] += 1
        if first_outcome_downgrade is not None:
            first_outcome_downgrade_phase[
                int(first_outcome_downgrade["phase"])
            ] += 1
            first_outcome_downgrade_source[
                str(first_outcome_downgrade["source"])
            ] += 1
        initial_disadvantage = int(first["best_score"]) <= -MAJOR_REGRET
        if first_major is not None and not initial_disadvantage:
            non_initial_first_major_phase[int(first_major["phase"])] += 1
        worst = max(rows, key=lambda row: int(row["regret"]))
        loss_details.append(
            {
                "game": game,
                "opening": int(first["opening"]),
                "margin": int(first["margin"]),
                "initial_edax_score": int(first["best_score"]),
                "initial_disadvantage": initial_disadvantage,
                "initial_source": first["source"],
                "first_major_ply": int(first_major["ply"])
                    if first_major is not None
                    else None,
                "first_major_phase": int(first_major["phase"])
                    if first_major is not None
                    else None,
                "first_major_source": first_major["source"]
                    if first_major is not None
                    else None,
                "first_major_regret": int(first_major["regret"])
                    if first_major is not None
                    else None,
                "first_sign_flip_ply": int(first_sign_flip["ply"])
                    if first_sign_flip is not None
                    else None,
                "first_sign_flip_source": first_sign_flip["source"]
                    if first_sign_flip is not None
                    else None,
                "first_outcome_downgrade_ply": int(
                    first_outcome_downgrade["ply"]
                )
                if first_outcome_downgrade is not None
                else None,
                "first_outcome_downgrade_phase": int(
                    first_outcome_downgrade["phase"]
                )
                if first_outcome_downgrade is not None
                else None,
                "first_outcome_downgrade_source":
                    first_outcome_downgrade["source"]
                    if first_outcome_downgrade is not None
                    else None,
                "worst_ply": int(worst["ply"]),
                "worst_phase": int(worst["phase"]),
                "worst_source": worst["source"],
                "worst_regret": int(worst["regret"]),
            }
        )

    initial_disadvantage = sum(
        detail["initial_edax_score"] <= -MAJOR_REGRET
        for detail in loss_details
    )
    book_first_major = sum(
        detail["first_major_source"] == "book" for detail in loss_details
    )
    search_first_major = sum(
        detail["first_major_source"] == "search" for detail in loss_details
    )
    return {
        "thresholds": {
            "major_regret_discs": MAJOR_REGRET,
            "phase_starts": [20, 30, 40, 50],
        },
        "match": games,
        "scored_decisions": len(decisions),
        "by_outcome": by_outcome,
        "by_phase": by_phase,
        "by_source": by_source,
        "by_outcome_phase": by_outcome_phase,
        "by_outcome_source": by_outcome_source,
        "opening_usage_by_outcome": opening_usage,
        "loss_attribution": {
            "losses": len(loss_details),
            "initial_disadvantage": initial_disadvantage,
            "book_first_major": book_first_major,
            "search_first_major": search_first_major,
            "no_major_regret": len(loss_details)
                - book_first_major
                - search_first_major,
            "first_major_phase": {
                str(phase): first_major_phase[phase] for phase in range(4)
            },
            "first_sign_flip_phase": {
                str(phase): first_sign_flip_phase[phase] for phase in range(4)
            },
            "first_outcome_downgrade_phase": {
                str(phase): first_outcome_downgrade_phase[phase]
                for phase in range(4)
            },
            "first_outcome_downgrade_source": {
                source: first_outcome_downgrade_source[source]
                for source in ("book", "search")
            },
            "non_initial_first_major_phase": {
                str(phase): non_initial_first_major_phase[phase]
                for phase in range(4)
            },
        },
        "loss_details": loss_details,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze move-level causes in EvaluationMatchRunner traces.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser(
        "prepare",
        help="Create all legal child positions for Edax batch scoring.",
    )
    prepare.add_argument("trace", type=Path)
    prepare.add_argument("output", type=Path)
    prepare.add_argument(
        "--color",
        choices=("black", "white"),
        default="white",
    )

    openings = subparsers.add_parser(
        "openings",
        help="Extract one initial position for each paired opening.",
    )
    openings.add_argument("trace", type=Path)
    openings.add_argument("output", type=Path)

    report = subparsers.add_parser(
        "report",
        help="Summarize a trace and its Edax-scored sibling positions.",
    )
    report.add_argument("trace", type=Path)
    report.add_argument("scored", type=Path)
    report.add_argument("output", type=Path)

    opening_report = subparsers.add_parser(
        "opening-report",
        help="Measure how initial Edax value affects paired match colors.",
    )
    opening_report.add_argument("match", type=Path)
    opening_report.add_argument("scored", type=Path)
    opening_report.add_argument("output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.command == "prepare":
        rows = prepare_rows(read_tsv(args.trace), args.color)
        write_tsv(rows, args.output)
        decisions = len({row["decision_id"] for row in rows})
        print(
            f"loss analysis positions written: {args.output} "
            f"decisions={decisions} positions={len(rows)}"
        )
        return 0

    if args.command == "openings":
        rows = prepare_opening_rows(read_tsv(args.trace))
        write_tsv(rows, args.output)
        print(
            f"opening positions written: {args.output} openings={len(rows)}"
        )
        return 0

    if args.command == "report":
        result = summarize(read_tsv(args.trace), read_tsv(args.scored))
    else:
        result = summarize_opening_bias(
            read_match_games(args.match),
            read_tsv(args.scored),
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"loss analysis written: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
