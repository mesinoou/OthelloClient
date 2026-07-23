from __future__ import annotations

import argparse
import struct
import io
from pathlib import Path
import tempfile
import unittest
import zlib

import numpy as np

from training.othello import (
    BLACK,
    CORNERS,
    Position,
    WHITE,
    apply_move,
    frontier_counts,
    initial_position,
    legal_moves,
    neighbors,
    parity_access_difference,
    source_coordinate_to_square,
    stable_edge_discs,
    wthor_move_code_to_square,
)
from training.patterns import PATTERN_GROUPS, encode_group
from training.export_java_model import (
    AUXILIARY_TABLES,
    MAGIC,
    PATTERN_TABLES,
    export_java_model,
    reverse_ternary_indices,
)
from training.java_model import (
    JavaEvaluationModel,
    adjust_java_model_bias,
    evaluate_java_model_components,
    evaluate_java_model_features,
    interpolate_java_models,
    merge_java_models,
    read_java_model,
    scale_java_model,
    write_java_model,
)
from training.train_model import (
    PAIR_BRANCHES,
    SCALAR_BRANCHES,
    PatternModel,
    ProgressBar,
    evaluate_quantized,
    labels,
    objective_values_and_gradient,
    pattern_table_key,
    swap_pattern_colors,
    torch,
    wld_targets,
)
from training.train_search_correction import (
    apply_phase_starts,
    concatenate_datasets,
    parse_phase_starts,
    sample_weights,
    source_correction_metrics,
    teacher_scores_normalized,
    zero_output_layers,
)
from training.wthor import read_wtb
from training.build_corpus import (
    canonical_position_key,
    load_wthor_registry,
    transform_bitboard,
)
from training.materialize_dataset import aggregate_observations
from training.evaluate_ranking import average_ranks, pairwise_accuracy
from training.analyze_match_pairs import read_opening_pairs
from training.generate_edax_teacher import (
    parse_result_line,
    server_position_to_obf,
)
from training.sample_ranking_positions import phase_ids
from training.train_potential_mobility_correction import fit_phase_table
from training.audit_evaluator_architecture import (
    AntisymmetricHead,
    balance_source_weights,
)


class OthelloTrainingPipelineTest(unittest.TestCase):
    def test_ranking_phase_boundaries_match_model_phases(self) -> None:
        ply = np.asarray([8, 19, 20, 29, 30, 39, 40, 52], dtype=np.uint8)
        np.testing.assert_array_equal(
            phase_ids(ply),
            np.asarray([0, 0, 0, 0, 1, 1, 2, 3]),
        )

    def test_search_correction_supports_analysis_phase_boundaries(self) -> None:
        starts = parse_phase_starts(
            "14,25,30,35,40,45,50,56",
            (20, 30, 40, 50),
        )
        data = {
            "ply": np.asarray(
                (14, 24, 25, 29, 30, 55, 56, 59),
                dtype=np.uint8,
            ),
            "phase": np.zeros(8, dtype=np.int8),
        }
        apply_phase_starts(data, starts)
        np.testing.assert_array_equal(
            data["phase"],
            np.asarray((0, 0, 1, 1, 2, 6, 7, 7), dtype=np.int8),
        )

    def test_ranking_metrics_handle_ties(self) -> None:
        values = np.asarray([30, 10, 30, 20])
        np.testing.assert_allclose(average_ranks(values), (2.5, 0.0, 2.5, 1.0))
        target = np.asarray([3, 2, 1])
        self.assertAlmostEqual(
            5.0 / 6.0,
            pairwise_accuracy(np.asarray([2, 2, 1]), target),
        )

    def test_match_pair_reader_clusters_both_colors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "match.txt"
            path.write_text(
                "game=1/2 opening=1 learned=black result=WIN "
                "margin=+8 discs=36-28 moves=60 elapsed=1.0s\n"
                "game=2/2 opening=1 learned=white result=DRAW "
                "margin=+0 discs=32-32 moves=60 elapsed=1.0s\n",
                encoding="utf-8",
            )
            scores, margins = read_opening_pairs(path)
            np.testing.assert_allclose(scores, (0.75,))
            np.testing.assert_allclose(margins, (4.0,))

    def test_edax_obf_mirrors_server_initial_position(self) -> None:
        black, white = initial_position()
        self.assertEqual(
            "---------------------------OX------XO--------------------------- X",
            server_position_to_obf(black, white),
        )

    def test_edax_obf_mirrors_asymmetric_server_position(self) -> None:
        obf = server_position_to_obf(1 << 0, 1 << 9)
        self.assertEqual("X", obf[7])
        self.assertEqual("O", obf[14])
        self.assertEqual(" X", obf[64:])

    def test_edax_solver_result_parser(self) -> None:
        result = parse_result_line(
            " 12|   11   -04        0:00.125        123456"
            "  d3 E3 f4"
        )
        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(12, result.index)
        self.assertEqual(11, result.depth)
        self.assertEqual(-4, result.score)
        self.assertEqual(125, result.time_ms)
        self.assertEqual(123456, result.nodes)
        self.assertEqual("d3 E3 f4", result.pv)

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

    def test_potential_mobility_is_color_antisymmetric(self) -> None:
        black, white = initial_position()
        empty = ~(black | white) & ((1 << 64) - 1)
        black_potential = (neighbors(white) & empty).bit_count()
        white_potential = (neighbors(black) & empty).bit_count()
        self.assertEqual((10, 10), (black_potential, white_potential))

        own = 1 << 0
        opponent = 1 << 9
        empty = ~(own | opponent) & ((1 << 64) - 1)
        self.assertEqual(
            (7, 2),
            (
                (neighbors(opponent) & empty).bit_count(),
                (neighbors(own) & empty).bit_count(),
            ),
        )

    def test_potential_mobility_table_is_color_antisymmetric(self) -> None:
        data = {
            "phase": np.asarray((0, 0), dtype=np.int8),
            "static_score": np.asarray((0, 0), dtype=np.int32),
            "edax_score": np.asarray((1, -1), dtype=np.int16),
            "occurrences": np.asarray((1, 1), dtype=np.uint32),
            "potential_mobility_own": np.asarray((1, 2), dtype=np.uint8),
            "potential_mobility_opponent": np.asarray(
                (2, 1),
                dtype=np.uint8,
            ),
        }
        table = fit_phase_table(data, 0, 6400, 0.0)
        self.assertEqual(100.0, table[1, 2])
        self.assertEqual(-100.0, table[2, 1])
        self.assertEqual(0.0, table[1, 1])

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
        data[14] = 24
        data[16 + 6] = 40
        data[16 + 7] = 35
        data[16 + 8] = 56

        games = read_wtb(bytes(data), "test.zip", "WTH_2099.WTB")

        self.assertEqual(1, len(games))
        self.assertEqual(40, games[0].black_score)
        self.assertEqual(35, games[0].theoretical_black_score)
        self.assertEqual(24, games[0].theoretical_empties)
        self.assertEqual((wthor_move_code_to_square(56),), games[0].moves)
        self.assertEqual("wthor:test.zip:WTH_2099.WTB:0", games[0].split_key)

    def test_full_wthor_registry_has_official_total(self) -> None:
        registry = load_wthor_registry()
        self.assertEqual(137548, registry["expected_total_games"])
        self.assertEqual(37, len(registry["archives"]))

    def test_opening_key_is_invariant_under_board_symmetry(self) -> None:
        black, white = initial_position()
        original = Position(black | 1, white, BLACK, 1)
        transformed = Position(
            transform_bitboard(original.black, 1),
            transform_bitboard(original.white, 1),
            BLACK,
            1,
        )
        self.assertEqual(
            canonical_position_key(original),
            canonical_position_key(transformed),
        )

    def test_corpus_aggregation_preserves_soft_and_theoretical_labels(self) -> None:
        black, white = initial_position()
        data = {
            "black": np.asarray([black, black], dtype=np.uint64),
            "white": np.asarray([white, white], dtype=np.uint64),
            "player": np.asarray([BLACK, BLACK], dtype=np.int8),
            "ply": np.asarray([0, 0], dtype=np.uint8),
            "game_id": np.asarray([0, 1], dtype=np.uint32),
            "source": np.asarray([0, 1], dtype=np.uint8),
            "label_disc": np.asarray([12, -4], dtype=np.int8),
            "label_filled": np.asarray([16, -8], dtype=np.int8),
            "theoretical_disc": np.asarray([127, 6], dtype=np.int8),
        }
        aggregated = aggregate_observations(data)
        self.assertEqual(1, len(aggregated["player"]))
        self.assertEqual(2, int(aggregated["sample_count"][0]))
        self.assertEqual(3, int(aggregated["source_mask"][0]))
        self.assertEqual(1, int(aggregated["black_wins"][0]))
        self.assertEqual(1, int(aggregated["white_wins"][0]))
        self.assertAlmostEqual(4.0, float(aggregated["label_disc"][0]))
        self.assertAlmostEqual(6.0, float(aggregated["teacher_disc"][0]))

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
            "teacher_filled": np.asarray([32.0, 32.0], dtype=np.float32),
            "player": np.asarray([BLACK, WHITE], dtype=np.int8),
        }
        actual = labels(data, np.asarray([0, 1]), "filled")[:, 0]
        np.testing.assert_allclose(actual, (0.25, -0.25))
        teacher = labels(data, np.asarray([0, 1]), "teacher-filled")[:, 0]
        np.testing.assert_allclose(teacher, (0.5, -0.5))

    def test_wld_targets_use_soft_side_to_move_score(self) -> None:
        data = {
            "player": np.asarray([BLACK, WHITE], dtype=np.int8),
            "sample_count": np.asarray([4, 4], dtype=np.uint32),
            "black_wins": np.asarray([3, 3], dtype=np.uint64),
            "draws": np.asarray([1, 1], dtype=np.uint64),
            "white_wins": np.asarray([0, 0], dtype=np.uint64),
        }
        actual = wld_targets(data, np.asarray([0, 1]))[:, 0]
        np.testing.assert_allclose(actual, (0.875, 0.125))

    def test_objectives_preserve_color_antisymmetry(self) -> None:
        prediction = np.asarray([[0.7], [-0.7]], dtype=np.float32)
        margin = np.asarray([[0.4], [-0.4]], dtype=np.float32)
        wld = np.asarray([[0.8], [0.2]], dtype=np.float32)
        for loss_name in ("mse", "huber", "wld", "hybrid"):
            values, gradient = objective_values_and_gradient(
                prediction,
                margin,
                wld,
                loss_name,
                1.0,
                0.25,
                4.0,
            )
            self.assertTrue(np.isfinite(values).all(), loss_name)
            self.assertTrue(np.isfinite(gradient).all(), loss_name)
            self.assertAlmostEqual(
                float(values[0, 0]),
                float(values[1, 0]),
                places=6,
                msg=loss_name,
            )
            self.assertAlmostEqual(
                float(gradient[0, 0]),
                -float(gradient[1, 0]),
                places=6,
                msg=loss_name,
            )

    def test_progress_bar_reports_completion(self) -> None:
        output = io.StringIO()
        progress = ProgressBar("train", 4, True, stream=output)
        progress.update(4)
        self.assertIn("100.0%", output.getvalue())

    def test_java_model_export_merges_reverse_patterns(self) -> None:
        self.assertEqual(3, int(reverse_ternary_indices(2)[1]))
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source_path = directory / "evaluation-tables.npz"
            output_path = directory / "evaluation-tables.bin"
            payload: dict[str, np.ndarray] = {
                "phase_starts": np.asarray((20, 30, 40, 50), dtype=np.int16),
                "score_scale": np.asarray((6400,), dtype=np.int32),
                "phase_bias": np.zeros(4, dtype=np.int32),
            }
            for phase in range(4):
                for suffix, digits, _, _ in PATTERN_TABLES:
                    values = np.zeros(3**digits, dtype=np.int16)
                    if suffix == "diagonal":
                        values[1] = 5
                        values[3**7] = 7
                    payload[f"phase{phase}_{suffix}"] = values
                for suffix, length in AUXILIARY_TABLES:
                    payload[f"phase{phase}_{suffix}"] = np.zeros(
                        length,
                        dtype=np.int16,
                    )
            np.savez_compressed(source_path, **payload)

            result = export_java_model(source_path, output_path)
            binary = output_path.read_bytes()
            self.assertEqual(MAGIC, struct.unpack_from(">i", binary, 0)[0])
            self.assertEqual(
                zlib.crc32(binary[:-4]) & 0xFFFFFFFF,
                struct.unpack_from(">I", binary, len(binary) - 4)[0],
            )
            first_table_offset = 8 * 4 + 4 * 4 + 4 + 4
            self.assertEqual(
                12,
                struct.unpack_from(">h", binary, first_table_offset + 2)[0],
            )
            self.assertEqual(1, result["score_divisor"])

            loaded = read_java_model(output_path)
            features: dict[str, np.ndarray] = {
                "ply": np.asarray((10,), dtype=np.uint8),
                "mobility_own": np.zeros(1, dtype=np.int8),
                "mobility_opponent": np.zeros(1, dtype=np.int8),
                "frontier_own": np.zeros(1, dtype=np.int8),
                "frontier_opponent": np.zeros(1, dtype=np.int8),
                "disc_difference": np.zeros(1, dtype=np.int8),
                "corner_difference": np.zeros(1, dtype=np.int8),
                "corner_move_difference": np.zeros(1, dtype=np.int8),
                "stable_edge_difference": np.zeros(1, dtype=np.int8),
                "parity_access_difference": np.zeros(1, dtype=np.int8),
            }
            for name, group in PATTERN_GROUPS.items():
                features[name] = np.zeros(
                    (1, group.instances),
                    dtype=np.int32,
                )
            features["diagonal"][0, 0] = 1
            np.testing.assert_array_equal(
                evaluate_java_model_features(loaded, features),
                np.asarray((12,), dtype=np.int32),
            )
            components = evaluate_java_model_components(loaded, features)
            self.assertEqual((1, 16), components.shape)
            self.assertEqual(
                12,
                int(components.sum()) + loaded.phase_bias[0],
            )

            roundtrip_path = directory / "roundtrip.bin"
            write_java_model(loaded, roundtrip_path)
            self.assertEqual(binary, roundtrip_path.read_bytes())

            zero = JavaEvaluationModel(
                phase_starts=loaded.phase_starts,
                score_scale=loaded.score_scale,
                score_divisor=1,
                phase_bias=(0, 0, 0, 0),
                tables=tuple(
                    tuple(np.zeros_like(table) for table in phase)
                    for phase in loaded.tables
                ),
            )
            correction_path = directory / "zero.bin"
            combined_path = directory / "combined.bin"
            write_java_model(zero, correction_path)
            merge_java_models(output_path, correction_path, combined_path)
            self.assertEqual(binary, combined_path.read_bytes())

            half_path = directory / "half.bin"
            scale_java_model(output_path, half_path, 0.5)
            half = read_java_model(half_path)
            np.testing.assert_array_equal(
                half.tables[0][0],
                np.rint(loaded.tables[0][0].astype(np.float64) * 0.5),
            )
            phase_path = directory / "phase.bin"
            scale_java_model(
                output_path,
                phase_path,
                1.0,
                (1.0, 0.0, 0.0, 0.0),
            )
            phase_model = read_java_model(phase_path)
            self.assertTrue(phase_model.tables[0][0].any())
            self.assertFalse(phase_model.tables[1][0].any())

            bias_path = directory / "bias.bin"
            adjust_java_model_bias(
                output_path,
                bias_path,
                (100, 200, 300, 400),
            )
            biased = read_java_model(bias_path)
            self.assertEqual((100, 200, 300, 400), biased.phase_bias)
            for original, adjusted in zip(
                loaded.tables,
                biased.tables,
                strict=True,
            ):
                for original_table, adjusted_table in zip(
                    original,
                    adjusted,
                    strict=True,
                ):
                    np.testing.assert_array_equal(
                        original_table,
                        adjusted_table,
                    )

            blend_zero_path = directory / "blend-zero.bin"
            interpolate_java_models(
                output_path,
                bias_path,
                blend_zero_path,
                0.0,
            )
            self.assertEqual(binary, blend_zero_path.read_bytes())
            blend_one_path = directory / "blend-one.bin"
            interpolate_java_models(
                output_path,
                bias_path,
                blend_one_path,
                1.0,
            )
            self.assertEqual(
                bias_path.read_bytes(),
                blend_one_path.read_bytes(),
            )
            blend_phase_path = directory / "blend-phase.bin"
            interpolate_java_models(
                output_path,
                bias_path,
                blend_phase_path,
                1.0,
                (1.0, 0.0, 0.0, 0.0),
            )
            blended_phase = read_java_model(blend_phase_path)
            self.assertEqual(100, blended_phase.phase_bias[0])
            self.assertEqual(0, blended_phase.phase_bias[1])

    def test_search_correction_maps_terminal_scores_to_margin_scale(self) -> None:
        scores = np.asarray(
            (100_012, -100_008, 640, -320, 0),
            dtype=np.int32,
        )
        np.testing.assert_allclose(
            teacher_scores_normalized(scores, 6400),
            np.asarray((12 / 64, -8 / 64, 0.1, -0.05, 0.0)),
        )

    def test_search_correction_starts_with_zero_output_layers(self) -> None:
        model = PatternModel(np.random.default_rng(7))
        zero_output_layers(model)
        for branch in (*PATTERN_GROUPS, *PAIR_BRANCHES, *SCALAR_BRANCHES):
            layers = [
                int(name.rsplit("_w", 1)[1])
                for name in model.parameters
                if name.startswith(branch + "_w")
            ]
            last = max(layers)
            self.assertFalse(model.parameters[f"{branch}_w{last}"].any())
            self.assertFalse(model.parameters[f"{branch}_b{last}"].any())

    def test_search_correction_balances_dataset_sources(self) -> None:
        data = {
            "occurrences": np.asarray((1, 4, 9, 16), dtype=np.uint32),
            "_source": np.asarray((0, 0, 0, 1), dtype=np.int8),
        }
        indices = np.arange(4)
        weights = sample_weights(data, indices, 8.0, True)
        self.assertAlmostEqual(float(weights[:3].sum()), float(weights[3]))

        target = np.asarray((0.2, -0.1, 0.3, -0.2), dtype=np.float32)
        metrics = source_correction_metrics(
            np.zeros(4, dtype=np.float32),
            target,
            weights,
            data["_source"],
            0.1,
        )
        self.assertEqual(2, len(metrics))
        for source in metrics:
            self.assertAlmostEqual(1.0, float(source["huber_ratio"]))

    def test_architecture_audit_balances_sources_per_phase(self) -> None:
        weights = balance_source_weights(
            np.asarray((1.0, 2.0, 4.0, 8.0), dtype=np.float32),
            np.asarray((0, 0, 0, 1), dtype=np.int8),
        )
        self.assertAlmostEqual(float(weights[:3].sum()), float(weights[3]))

    @unittest.skipIf(torch is None, "PyTorch is not installed")
    def test_interaction_head_is_color_antisymmetric(self) -> None:
        torch.manual_seed(17)
        head = AntisymmetricHead(3, 2, 4)
        with torch.no_grad():
            for parameter in head.parameters():
                parameter.copy_(torch.randn_like(parameter))
            signed = torch.randn(5, 3)
            context = torch.randn(5, 2)
            positive = head(signed, context)
            negative = head(-signed, context)
        np.testing.assert_allclose(
            positive.numpy(),
            -negative.numpy(),
            atol=1.0e-6,
        )

    def test_search_correction_combines_only_common_arrays(self) -> None:
        combined = concatenate_datasets(
            [
                {
                    "required": np.asarray((1, 2)),
                    "optional": np.asarray((3, 4)),
                },
                {"required": np.asarray((5,))},
            ]
        )
        self.assertEqual({"required"}, set(combined))
        np.testing.assert_array_equal(
            combined["required"],
            np.asarray((1, 2, 5)),
        )

    def test_quantized_evaluation_applies_java_score_divisor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            tables_path = Path(temporary) / "evaluation-tables.npz"
            payload: dict[str, np.ndarray] = {
                "score_scale": np.asarray((100,), dtype=np.int32),
                "phase_bias": np.asarray((100, 0, 0, 0), dtype=np.int32),
            }
            data: dict[str, np.ndarray] = {
                "label_filled": np.asarray((16,), dtype=np.int8),
                "label_disc": np.asarray((16,), dtype=np.int8),
                "player": np.asarray((BLACK,), dtype=np.int8),
                "sample_count": np.asarray((1,), dtype=np.uint32),
                "black_wins": np.asarray((1,), dtype=np.uint64),
                "draws": np.asarray((0,), dtype=np.uint64),
                "white_wins": np.asarray((0,), dtype=np.uint64),
            }
            for name, group in PATTERN_GROUPS.items():
                data[name] = np.zeros(
                    (1, group.instances),
                    dtype=np.int32,
                )
                for class_id in range(group.class_count or 1):
                    payload[pattern_table_key(0, name, class_id)] = np.zeros(
                        3 ** group.size_for_class(class_id),
                        dtype=np.int16,
                    )
            for name, specs in PAIR_BRANCHES.items():
                for field, _, _ in specs:
                    data[field] = np.zeros(1, dtype=np.int8)
                payload[f"phase0_{name}"] = np.zeros(
                    (65, 65),
                    dtype=np.int16,
                )
            for name, (minimum, maximum, _) in SCALAR_BRANCHES.items():
                data[name] = np.zeros(1, dtype=np.int8)
                payload[f"phase0_{name}"] = np.zeros(
                    maximum - minimum + 1,
                    dtype=np.int16,
                )
            np.savez_compressed(tables_path, **payload)
            args = argparse.Namespace(
                label="filled",
                loss="mse",
                margin_loss_weight=1.0,
                huber_delta=0.25,
                wld_logit_scale=4.0,
            )

            metrics = evaluate_quantized(
                tables_path,
                data,
                np.asarray((0,)),
                0,
                args,
                score_divisor=2,
            )

            self.assertAlmostEqual(0.0625, metrics.mse)


if __name__ == "__main__":
    unittest.main()
