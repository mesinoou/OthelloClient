from __future__ import annotations

import struct
import io
import unittest

import numpy as np

from training.othello import (
    BLACK,
    CORNERS,
    WHITE,
    apply_move,
    frontier_counts,
    initial_position,
    legal_moves,
    parity_access_difference,
    source_coordinate_to_square,
    stable_edge_discs,
    wthor_move_code_to_square,
)
from training.patterns import PATTERN_GROUPS, encode_group
from training.train_model import (
    PatternModel,
    ProgressBar,
    labels,
    swap_pattern_colors,
)
from training.wthor import read_wtb


class OthelloTrainingPipelineTest(unittest.TestCase):
    def test_initial_moves_match_mirrored_source_coordinates(self) -> None:
        black, white = initial_position()
        moves = legal_moves(black, white)
        source_moves = ("c4", "d3", "e6", "f5")
        mirrored = {
            source_coordinate_to_square(coordinate)
            for coordinate in source_moves
        }
        actual = {
            square for square in range(64) if moves & (1 << square)
        }
        self.assertEqual(mirrored, actual)

    def test_first_record_prefix_is_legal(self) -> None:
        black, white = initial_position()
        player = BLACK
        for coordinate in ("f5", "d6", "c3", "d3", "c4", "f4"):
            square = source_coordinate_to_square(coordinate)
            own = black if player == BLACK else white
            other = white if player == BLACK else black
            self.assertNotEqual(0, legal_moves(own, other) & (1 << square))
            black, white = apply_move(black, white, player, square)
            player = -player
        self.assertEqual(10, (black | white).bit_count())

    def test_wthor_first_move_matches_server_orientation(self) -> None:
        black, white = initial_position()
        square = wthor_move_code_to_square(56)
        self.assertNotEqual(0, legal_moves(black, white) & (1 << square))

    def test_wthor_reader_parses_game_record(self) -> None:
        data = bytearray(16 + 68)
        struct.pack_into("<i", data, 4, 1)
        data[12] = 8
        data[16 + 6] = 40
        data[16 + 8] = 56

        games = read_wtb(bytes(data), "test.zip", "WTH_2099.WTB")

        self.assertEqual(1, len(games))
        self.assertEqual(40, games[0].black_score)
        self.assertEqual((wthor_move_code_to_square(56),), games[0].moves)
        self.assertEqual("wthor:test.zip:WTH_2099.WTB:0", games[0].split_key)

    def test_patterns_stay_inside_declared_ranges(self) -> None:
        black, white = initial_position()
        self.assertEqual(6, len(PATTERN_GROUPS))
        for name, group in PATTERN_GROUPS.items():
            encoded = encode_group(black, white, group)
            self.assertEqual(group.instances, len(encoded), name)
            for value, pattern in zip(encoded, group.patterns, strict=True):
                self.assertTrue(0 <= value < 3 ** len(pattern), name)

    def test_frontier_is_color_symmetric(self) -> None:
        black, white = initial_position()
        black_frontier, white_frontier = frontier_counts(black, white)
        swapped_white, swapped_black = frontier_counts(white, black)
        self.assertEqual(black_frontier, swapped_black)
        self.assertEqual(white_frontier, swapped_white)

    def test_model_forward_and_backward_are_finite(self) -> None:
        rng = np.random.default_rng(7)
        model = PatternModel(rng)
        count = 8
        data = {}
        for name, group in PATTERN_GROUPS.items():
            data[name] = np.column_stack(
                tuple(
                    rng.integers(
                        0,
                        3 ** len(pattern),
                        size=count,
                        dtype=np.uint16,
                    )
                    for pattern in group.patterns
                )
            )
        data.update(
            {
                "mobility_own": rng.integers(0, 20, size=count, dtype=np.uint8),
                "mobility_opponent": rng.integers(
                    0,
                    20,
                    size=count,
                    dtype=np.uint8,
                ),
                "frontier_own": rng.integers(0, 33, size=count, dtype=np.uint8),
                "frontier_opponent": rng.integers(
                    0,
                    33,
                    size=count,
                    dtype=np.uint8,
                ),
                "disc_difference": rng.integers(
                    -20,
                    21,
                    size=count,
                    dtype=np.int8,
                ),
                "corner_difference": rng.integers(
                    -4,
                    5,
                    size=count,
                    dtype=np.int8,
                ),
                "corner_move_difference": rng.integers(
                    -4,
                    5,
                    size=count,
                    dtype=np.int8,
                ),
                "stable_edge_difference": rng.integers(
                    -10,
                    11,
                    size=count,
                    dtype=np.int8,
                ),
                "parity_access_difference": rng.integers(
                    -8,
                    9,
                    size=count,
                    dtype=np.int8,
                ),
            }
        )
        indices = np.arange(count)
        prediction, cache = model.forward(data, indices, cache=True)
        gradients = model.backward(
            np.ones_like(prediction) / count,
            cache,
            1.0e-5,
        )
        self.assertEqual((count, 1), prediction.shape)
        self.assertTrue(np.isfinite(prediction).all())
        self.assertEqual(set(model.parameters), set(gradients))
        self.assertTrue(all(np.isfinite(value).all() for value in gradients.values()))

        swapped = {
            name: swap_pattern_colors(data[name], group.max_squares)
            for name, group in PATTERN_GROUPS.items()
        }
        swapped.update(
            {
                "mobility_own": data["mobility_opponent"],
                "mobility_opponent": data["mobility_own"],
                "frontier_own": data["frontier_opponent"],
                "frontier_opponent": data["frontier_own"],
                **{
                    name: -data[name]
                    for name in (
                        "disc_difference",
                        "corner_difference",
                        "corner_move_difference",
                        "stable_edge_difference",
                        "parity_access_difference",
                    )
                },
            }
        )
        swapped_prediction, _ = model.forward(swapped, indices, cache=False)
        np.testing.assert_allclose(prediction, -swapped_prediction, atol=1.0e-6)

    def test_baseline_features_change_sign_when_colors_swap(self) -> None:
        black, white = initial_position()
        black |= CORNERS & -CORNERS
        occupied = black | white
        black_stable = stable_edge_discs(black, occupied).bit_count()
        white_stable = stable_edge_discs(white, occupied).bit_count()
        self.assertEqual(1, black_stable)
        self.assertEqual(0, white_stable)
        self.assertEqual(
            -(white_stable - black_stable),
            black_stable - white_stable,
        )
        black_moves = legal_moves(black, white)
        white_moves = legal_moves(white, black)
        self.assertEqual(
            -parity_access_difference(white, black, white_moves, black_moves),
            parity_access_difference(black, white, black_moves, white_moves),
        )

    def test_labels_use_side_to_move_perspective(self) -> None:
        data = {
            "label_filled": np.asarray([16, 16], dtype=np.int8),
            "label_disc": np.asarray([8, 8], dtype=np.int8),
            "player": np.asarray([BLACK, WHITE], dtype=np.int8),
        }
        actual = labels(data, np.asarray([0, 1]), "filled")[:, 0]
        np.testing.assert_allclose(actual, (0.25, -0.25))

    def test_progress_bar_reports_completion(self) -> None:
        output = io.StringIO()
        progress = ProgressBar("train", 4, True, stream=output)
        progress.update(4)
        self.assertIn("100.0%", output.getvalue())


if __name__ == "__main__":
    unittest.main()
