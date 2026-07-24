from __future__ import annotations

import unittest

from training.analyze_loss_trace import prepare_rows, summarize
from training.othello import WHITE, apply_move, initial_position, legal_moves


def trace_row(
    *,
    game: str,
    outcome: str,
    ply: str,
    source: str,
    margin: str = "-8",
) -> dict[str, str]:
    black, white = initial_position()
    moves = legal_moves(white, black)
    move = moves & -moves
    square = move.bit_length() - 1
    child_black, child_white = apply_move(black, white, WHITE, square)
    return {
        "game": game,
        "opening": game,
        "learned_color": "white",
        "outcome": outcome,
        "margin": margin,
        "ply": ply,
        "black": f"{black:016x}",
        "white": f"{white:016x}",
        "child_black": f"{child_black:016x}",
        "child_white": f"{child_white:016x}",
        "player": "white",
        "actor": "learned",
        "move": str(square),
        "source": source,
        "search_score": "-100",
        "completed_depth": "8",
        "elapsed_ms": "100.0",
        "timed_out": "true",
        "wld_search": "false",
        "wld_solution": "false",
    }


def scored_candidates(
    row: dict[str, str],
    best_value: int,
    chosen_value: int,
) -> list[dict[str, str]]:
    prepared = prepare_rows([row], "white")
    best_assigned = False
    for prepared_row in prepared:
        selected = prepared_row["selected"] == "true"
        if selected:
            value = chosen_value
        elif not best_assigned:
            value = best_value
            best_assigned = True
        else:
            value = best_value - 1
        prepared_row["edax_score"] = str(-value)
        prepared_row["edax_depth"] = "8"
        prepared_row["edax_time_ms"] = "10"
        prepared_row["edax_pv"] = "d3 c3"
    return prepared


class LossAnalysisTest(unittest.TestCase):
    def test_prepare_writes_parent_and_child_perspectives(self) -> None:
        row = trace_row(game="2", outcome="LOSS", ply="12", source="book")
        prepared = prepare_rows([row], "white")

        self.assertGreaterEqual(len(prepared), 2)
        self.assertTrue(all(row["player"] == "1" for row in prepared))
        selected = [row for row in prepared if row["selected"] == "true"]
        self.assertEqual(1, len(selected))
        self.assertEqual(row["child_white"], selected[0]["white"])

    def test_summary_attributes_first_major_regret(self) -> None:
        first = trace_row(
            game="2",
            outcome="LOSS",
            ply="12",
            source="book",
        )
        second = trace_row(
            game="2",
            outcome="LOSS",
            ply="32",
            source="search",
        )
        scored = scored_candidates(first, 3, 2)
        scored.extend(scored_candidates(second, 2, -3))

        result = summarize([first, second], scored)

        self.assertEqual(2, result["scored_decisions"])
        attribution = result["loss_attribution"]
        self.assertEqual(1, attribution["losses"])
        self.assertEqual(1, attribution["search_first_major"])
        self.assertEqual(1, attribution["first_major_phase"]["1"])
        detail = result["loss_details"][0]
        self.assertEqual(5, detail["first_major_regret"])
        self.assertEqual(32, detail["first_sign_flip_ply"])


if __name__ == "__main__":
    unittest.main()
