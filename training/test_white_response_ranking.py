from __future__ import annotations

import unittest

import numpy as np

from training.train_white_response_ranking import (
    grouped_local_rows,
    metric_selection_key,
    ranking_metrics,
    runtime_phase,
    split_openings,
    wld_class,
)


class WhiteResponseRankingTest(unittest.TestCase):
    def test_wld_class_and_runtime_phase(self) -> None:
        starts = (20, 30, 40, 50)

        self.assertEqual(-1, wld_class(-2))
        self.assertEqual(0, wld_class(0))
        self.assertEqual(1, wld_class(7))
        self.assertEqual(0, runtime_phase(29, starts))
        self.assertEqual(1, runtime_phase(30, starts))
        self.assertEqual(3, runtime_phase(55, starts))

    def test_opening_split_has_no_leakage(self) -> None:
        openings = np.repeat(np.arange(1, 13, dtype=np.int32), 3)
        split = split_openings(openings, 1.0 / 6.0, 1.0 / 6.0, 7)

        train = set(split["train"])
        validation = set(split["validation"])
        test = set(split["test"])
        self.assertFalse(train & validation)
        self.assertFalse(train & test)
        self.assertFalse(validation & test)
        self.assertEqual(set(range(1, 13)), train | validation | test)

    def test_grouping_uses_local_offsets(self) -> None:
        data = {"parent_id": np.asarray([8, 8, 9, 8, 9], dtype=np.int32)}
        groups = grouped_local_rows(data, np.asarray([1, 2, 3, 4]))

        self.assertEqual([[0, 2], [1, 3]], [group.tolist() for group in groups])

    def test_metrics_prioritize_wld_before_disc_margin(self) -> None:
        data = {
            "parent_id": np.asarray([0, 0, 0, 1, 1], dtype=np.int32),
            "teacher_score": np.asarray([1, 20, -1, 0, -20], dtype=np.int16),
            "teacher_wld": np.asarray([1, 1, -1, 0, -1], dtype=np.int8),
        }
        indices = np.arange(5, dtype=np.int64)
        prediction = np.asarray([3.0, 2.0, 1.0, 0.0, 2.0], dtype=np.float32)

        result = ranking_metrics(prediction, data, indices)

        self.assertEqual(2, result["decisions"])
        self.assertEqual(0.5, result["wld_top1_accuracy"])
        self.assertEqual(0.5, result["wld_downgrade_rate"])
        self.assertEqual(0.0, result["exact_top1_accuracy"])
        self.assertEqual(19.5, result["mean_disc_regret"])

    def test_selection_prefers_wld_safety_and_zero_when_tied(self) -> None:
        baseline = {
            "wld_downgrade_rate": 0.1,
            "mean_class_regret": 0.2,
            "major_regret_rate": 0.3,
            "mean_disc_regret": 2.0,
        }
        same_metrics = dict(baseline)
        safer = dict(baseline) | {"wld_downgrade_rate": 0.0}

        self.assertLess(
            metric_selection_key(safer, 0.2),
            metric_selection_key(baseline, 0.0),
        )
        self.assertLess(
            metric_selection_key(baseline, 0.0),
            metric_selection_key(same_metrics, 0.01),
        )


if __name__ == "__main__":
    unittest.main()
