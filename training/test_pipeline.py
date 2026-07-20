from __future__ import annotations

import struct
import unittest

import numpy as np

from training.othello import (
    BLACK,
    WHITE,
    apply_move,
    frontier_counts,
    initial_position,
    legal_moves,
    source_coordinate_to_square,
    wthor_move_code_to_square,
)
from training.patterns import PATTERN_GROUPS, encode_group
from training.train_model import PatternModel
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
        for name, patterns in PATTERN_GROUPS.items():
            encoded = encode_group(black, white, patterns)
            self.assertEqual(len(patterns), len(encoded))
            maximum = 3 ** len(patterns[0])
            self.assertTrue(all(0 <= value < maximum for value in encoded))

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
        data = {
            "diagonal": rng.integers(0, 3**8, size=(count, 4), dtype=np.uint16),
            "edge2x": rng.integers(0, 3**10, size=(count, 8), dtype=np.uint16),
            "corner": rng.integers(0, 3**10, size=(count, 8), dtype=np.uint16),
            "mobility": rng.integers(-15, 16, size=count, dtype=np.int8),
            "frontier_black": rng.integers(0, 33, size=count, dtype=np.uint8),
            "frontier_white": rng.integers(0, 33, size=count, dtype=np.uint8),
        }
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


if __name__ == "__main__":
    unittest.main()
