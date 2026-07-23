"""Screen additional local patterns against held-out Edax residuals."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import sys
from typing import Any

import numpy as np
import torch

from training.java_model import JavaEvaluationModel, read_java_model
from training.patterns import PATTERN_GROUPS


PHASE_COUNT = 4


@dataclass(frozen=True)
class CandidatePattern:
    name: str
    patterns: tuple[tuple[int, ...], ...]

    @property
    def digits(self) -> int:
        sizes = {len(pattern) for pattern in self.patterns}
        if len(sizes) != 1:
            raise ValueError(f"{self.name}: mixed pattern sizes")
        return sizes.pop()


def _square(x: int, y: int) -> int:
    return y * 8 + x


def _transform(
    pattern: tuple[int, ...],
    symmetry: int,
) -> tuple[int, ...]:
    transformed = []
    for square in pattern:
        x = square & 7
        y = square >> 3
        if symmetry & 4:
            x = 7 - x
        for _ in range(symmetry & 3):
            x, y = 7 - y, x
        transformed.append(_square(x, y))
    return tuple(transformed)


def _d4(pattern: tuple[int, ...]) -> tuple[tuple[int, ...], ...]:
    result = []
    seen = set()
    for symmetry in range(8):
        transformed = _transform(pattern, symmetry)
        if transformed not in seen:
            result.append(transformed)
            seen.add(transformed)
    return tuple(result)


CANDIDATE_PATTERNS = {
    "diagonal5": CandidatePattern(
        "diagonal5",
        _d4(tuple(_square(3 + offset, offset) for offset in range(5))),
    ),
    "diagonal4": CandidatePattern(
        "diagonal4",
        _d4(tuple(_square(4 + offset, offset) for offset in range(4))),
    ),
    "corner2x5": CandidatePattern(
        "corner2x5",
        _d4(tuple(_square(x, y) for y in range(2) for x in range(5))),
    ),
    "edge8": CandidatePattern(
        "edge8",
        _d4(tuple(_square(x, 0) for x in range(8))),
    ),
    "control_corner3x3": CandidatePattern(
        "control_corner3x3",
        PATTERN_GROUPS["corner3x3"].patterns,
    ),
}


def parse_float_list(value: str) -> tuple[float, ...]:
    result = tuple(float(item) for item in value.split(","))
    if not result or any(item < 0.0 for item in result):
        raise argparse.ArgumentTypeError(
            "values must be a non-empty list of non-negative numbers"
        )
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit candidate pattern tables on Edax residual holdout.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/edax-search-evaluation-full-l9"),
    )
    parser.add_argument(
        "--base-model",
        type=Path,
        default=Path(
            ".training/models/eval-010-edax-l9-iter2-a050-e160/"
            "evaluation-tables.bin"
        ),
    )
    parser.add_argument(
        "--static-model",
        type=Path,
        default=Path("data/evaluation-tables.bin"),
        help="model expected to match dataset static_score",
    )
    parser.add_argument(
        "--patterns",
        default=",".join(CANDIDATE_PATTERNS),
        help="comma-separated candidate names",
    )
    parser.add_argument(
        "--audit-model",
        choices=("table", "mlp"),
        default="mlp",
        help="candidate correction parameterization",
    )
    parser.add_argument("--epochs", type=int, default=300)
    parser.add_argument("--patience", type=int, default=30)
    parser.add_argument("--learning-rate", type=float, default=0.03)
    parser.add_argument(
        "--output-anchors",
        type=parse_float_list,
        default=(0.1, 0.5, 1.0),
    )
    parser.add_argument("--l2", type=float, default=1e-4)
    parser.add_argument("--occurrence-weight-cap", type=float, default=8.0)
    parser.add_argument("--seed", type=int, default=20260805)
    parser.add_argument("--device", default="auto")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(".training/audits/eval-014-pattern-residuals.json"),
    )
    return parser.parse_args()


def load_split(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        required = {
            "black",
            "white",
            "player",
            "phase",
            "occurrences",
            "static_score",
            "edax_score",
            "diagonal",
            "edge2x",
            "corner",
            "line8",
            "short_diagonal",
            "corner3x3",
            "mobility_own",
            "mobility_opponent",
            "frontier_own",
            "frontier_opponent",
            "disc_difference",
            "corner_difference",
            "corner_move_difference",
            "stable_edge_difference",
            "parity_access_difference",
        }
        missing = required - set(archive.files)
        if missing:
            raise ValueError(f"{path}: missing arrays {sorted(missing)}")
        return {name: archive[name] for name in archive.files}


def sample_weights(
    data: dict[str, np.ndarray],
    cap: float,
) -> np.ndarray:
    weights = np.sqrt(data["occurrences"].astype(np.float32))
    weights = np.minimum(weights, cap)
    return weights / weights.mean()


def encode_patterns(
    data: dict[str, np.ndarray],
    candidate: CandidatePattern,
) -> np.ndarray:
    player = data["player"]
    own = np.where(player == 1, data["black"], data["white"]).astype(
        np.uint64,
        copy=False,
    )
    opponent = np.where(
        player == 1,
        data["white"],
        data["black"],
    ).astype(np.uint64, copy=False)
    encoded = np.zeros(
        (len(player), len(candidate.patterns)),
        dtype=np.int64,
    )
    for instance, pattern in enumerate(candidate.patterns):
        values = np.zeros(len(player), dtype=np.int64)
        for square in pattern:
            own_digit = ((own >> np.uint64(square)) & np.uint64(1)).astype(
                np.int64
            )
            opponent_digit = (
                (opponent >> np.uint64(square)) & np.uint64(1)
            ).astype(np.int64)
            values = values * 3 + own_digit + 2 * opponent_digit
        encoded[:, instance] = values
    return encoded


def color_swap_map(digits: int) -> np.ndarray:
    count = 3**digits
    values = np.arange(count, dtype=np.int64)
    powers = 3 ** np.arange(digits - 1, -1, -1, dtype=np.int64)
    states = (values[:, None] // powers[None, :]) % 3
    swapped = np.where(states == 1, 2, np.where(states == 2, 1, 0))
    return (swapped * powers[None, :]).sum(axis=1).astype(np.int64)


def evaluate_java_model(
    model: JavaEvaluationModel,
    data: dict[str, np.ndarray],
) -> np.ndarray:
    if model.score_divisor != 1:
        raise ValueError("audit requires a score_divisor=1 model")
    phase_ids = data["phase"].astype(np.int64)
    scores = np.empty(len(phase_ids), dtype=np.int64)
    pattern_columns = (
        ("diagonal", (slice(0, 2),)),
        ("edge2x", (slice(0, 4),)),
        ("corner", (slice(0, 8),)),
        ("line8", (slice(0, 4), slice(8, 12), slice(16, 20))),
        ("short_diagonal", (slice(0, 4), slice(8, 12))),
        ("corner3x3", (slice(0, 8),)),
    )
    for phase in range(PHASE_COUNT):
        selected = np.flatnonzero(phase_ids == phase)
        phase_scores = np.full(
            len(selected),
            model.phase_bias[phase],
            dtype=np.int64,
        )
        table_index = 0
        for name, slices in pattern_columns:
            values = data[name][selected]
            for columns in slices:
                table = model.tables[phase][table_index].astype(np.int64)
                phase_scores += table[values[:, columns]].sum(axis=1)
                table_index += 1
        mobility_index = (
            data["mobility_own"][selected].astype(np.int64) * 65
            + data["mobility_opponent"][selected].astype(np.int64)
        )
        frontier_index = (
            data["frontier_own"][selected].astype(np.int64) * 65
            + data["frontier_opponent"][selected].astype(np.int64)
        )
        auxiliary_indices = (
            mobility_index,
            frontier_index,
            data["disc_difference"][selected].astype(np.int64) + 64,
            data["corner_difference"][selected].astype(np.int64) + 4,
            data["corner_move_difference"][selected].astype(np.int64) + 4,
            data["stable_edge_difference"][selected].astype(np.int64) + 28,
            data["parity_access_difference"][selected].astype(np.int64) + 32,
        )
        for indices in auxiliary_indices:
            phase_scores += model.tables[phase][table_index][indices].astype(
                np.int64
            )
            table_index += 1
        if table_index != len(model.tables[phase]):
            raise AssertionError("Java model table mapping is incomplete")
        scores[selected] = phase_scores
    return scores.astype(np.float32) / float(model.score_scale)


def residual_targets(
    model: JavaEvaluationModel,
    data: dict[str, np.ndarray],
) -> np.ndarray:
    prediction = evaluate_java_model(model, data)
    teacher = data["edax_score"].astype(np.float32) / 64.0
    return teacher - prediction


def weighted_metrics(
    error: np.ndarray,
    weights: np.ndarray,
) -> dict[str, float]:
    return {
        "mse": float(np.average(error**2, weights=weights)),
        "mae": float(np.average(np.abs(error), weights=weights)),
    }


def resolve_device(name: str) -> torch.device:
    if name == "auto":
        name = "cuda" if torch.cuda.is_available() else "cpu"
    if name.startswith("cuda") and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    return torch.device(name)


def train_table_phase(
    train_indices: np.ndarray,
    validation_indices: np.ndarray,
    train_target: np.ndarray,
    validation_target: np.ndarray,
    train_weights: np.ndarray,
    validation_weights: np.ndarray,
    states: int,
    swap_map: np.ndarray,
    args: argparse.Namespace,
    device: torch.device,
) -> tuple[np.ndarray, dict[str, Any]]:
    train_x = torch.as_tensor(
        train_indices,
        dtype=torch.long,
        device=device,
    )
    validation_x = torch.as_tensor(
        validation_indices,
        dtype=torch.long,
        device=device,
    )
    train_y = torch.as_tensor(train_target, device=device)
    validation_y = torch.as_tensor(validation_target, device=device)
    train_w = torch.as_tensor(train_weights, device=device)
    validation_w = torch.as_tensor(validation_weights, device=device)
    swap = torch.as_tensor(swap_map, dtype=torch.long, device=device)

    selections = []
    best_anchor = None
    best_validation = float("inf")
    best_table = None
    for anchor in args.output_anchors:
        table = torch.nn.Parameter(torch.zeros(states, device=device))
        optimizer = torch.optim.Adam((table,), lr=args.learning_rate)
        anchor_best = float("inf")
        anchor_table = None
        anchor_epoch = 0
        stale = 0
        for epoch in range(1, args.epochs + 1):
            optimizer.zero_grad(set_to_none=True)
            prediction = 0.5 * (
                table[train_x] - table[swap[train_x]]
            ).sum(dim=1)
            fit = ((prediction - train_y) ** 2 * train_w).mean()
            objective = (
                fit
                + anchor * (prediction**2).mean()
                + args.l2 * (table**2).mean()
            )
            objective.backward()
            optimizer.step()

            with torch.no_grad():
                validation_prediction = 0.5 * (
                    table[validation_x] - table[swap[validation_x]]
                ).sum(dim=1)
                validation_mse = float(
                    (
                        (validation_prediction - validation_y) ** 2
                        * validation_w
                    ).mean().item()
                )
            if validation_mse < anchor_best - 1e-9:
                anchor_best = validation_mse
                anchor_table = table.detach().cpu().numpy().copy()
                anchor_epoch = epoch
                stale = 0
            else:
                stale += 1
                if stale >= args.patience:
                    break
        if anchor_table is None:
            raise AssertionError("training did not produce a checkpoint")
        selections.append(
            {
                "output_anchor": anchor,
                "best_epoch": anchor_epoch,
                "validation_mse": anchor_best,
            }
        )
        if anchor_best < best_validation:
            best_validation = anchor_best
            best_anchor = anchor
            best_table = anchor_table
    if best_table is None or best_anchor is None:
        raise AssertionError("hyperparameter selection failed")
    return best_table, {
        "selected_output_anchor": best_anchor,
        "validation_mse": best_validation,
        "candidates": selections,
    }


class AntisymmetricPatternMlp(torch.nn.Module):
    def __init__(self, digits: int) -> None:
        super().__init__()
        self.digits = digits
        self.network = torch.nn.Sequential(
            torch.nn.Linear(2 * digits, 16),
            torch.nn.LeakyReLU(0.01),
            torch.nn.Linear(16, 16),
            torch.nn.LeakyReLU(0.01),
            torch.nn.Linear(16, 1),
        )
        torch.nn.init.zeros_(self.network[-1].weight)
        torch.nn.init.zeros_(self.network[-1].bias)

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        samples, instances, _ = inputs.shape
        flattened = inputs.reshape(samples * instances, -1)
        swapped = torch.cat(
            (
                flattened[:, self.digits :],
                flattened[:, : self.digits],
            ),
            dim=1,
        )
        values = self.network(flattened)
        swapped_values = self.network(swapped)
        return (
            0.5 * (values - swapped_values)
        ).reshape(samples, instances).sum(dim=1)


def decode_pattern_inputs(
    encoded: np.ndarray,
    digits: int,
) -> np.ndarray:
    powers = 3 ** np.arange(digits - 1, -1, -1, dtype=np.int64)
    states = (encoded[..., None] // powers) % 3
    return np.concatenate((states == 1, states == 2), axis=2).astype(
        np.float32
    )


def train_mlp_phase(
    train_inputs: np.ndarray,
    validation_inputs: np.ndarray,
    train_target: np.ndarray,
    validation_target: np.ndarray,
    train_weights: np.ndarray,
    validation_weights: np.ndarray,
    digits: int,
    args: argparse.Namespace,
    device: torch.device,
) -> tuple[dict[str, np.ndarray], dict[str, Any]]:
    train_x = torch.as_tensor(train_inputs, device=device)
    validation_x = torch.as_tensor(validation_inputs, device=device)
    train_y = torch.as_tensor(train_target, device=device)
    validation_y = torch.as_tensor(validation_target, device=device)
    train_w = torch.as_tensor(train_weights, device=device)
    validation_w = torch.as_tensor(validation_weights, device=device)

    selections = []
    best_anchor = None
    best_validation = float("inf")
    best_state = None
    for anchor_index, anchor in enumerate(args.output_anchors):
        torch.manual_seed(args.seed + anchor_index)
        if device.type == "cuda":
            torch.cuda.manual_seed_all(args.seed + anchor_index)
        model = AntisymmetricPatternMlp(digits).to(device)
        optimizer = torch.optim.Adam(
            model.parameters(),
            lr=args.learning_rate,
        )
        anchor_best = float("inf")
        anchor_state = None
        anchor_epoch = 0
        stale = 0
        for epoch in range(1, args.epochs + 1):
            optimizer.zero_grad(set_to_none=True)
            prediction = model(train_x)
            fit = ((prediction - train_y) ** 2 * train_w).mean()
            l2_penalty = torch.stack(
                tuple((parameter**2).mean() for parameter in model.parameters())
            ).sum()
            objective = (
                fit
                + anchor * (prediction**2).mean()
                + args.l2 * l2_penalty
            )
            objective.backward()
            optimizer.step()

            with torch.no_grad():
                validation_prediction = model(validation_x)
                validation_mse = float(
                    (
                        (validation_prediction - validation_y) ** 2
                        * validation_w
                    ).mean().item()
                )
            if validation_mse < anchor_best - 1e-9:
                anchor_best = validation_mse
                anchor_state = {
                    name: value.detach().cpu().numpy().copy()
                    for name, value in model.state_dict().items()
                }
                anchor_epoch = epoch
                stale = 0
            else:
                stale += 1
                if stale >= args.patience:
                    break
        if anchor_state is None:
            raise AssertionError("MLP training did not produce a checkpoint")
        selections.append(
            {
                "output_anchor": anchor,
                "best_epoch": anchor_epoch,
                "validation_mse": anchor_best,
            }
        )
        if anchor_best < best_validation:
            best_validation = anchor_best
            best_anchor = anchor
            best_state = anchor_state
    if best_state is None or best_anchor is None:
        raise AssertionError("MLP hyperparameter selection failed")
    return best_state, {
        "selected_output_anchor": best_anchor,
        "validation_mse": best_validation,
        "candidates": selections,
    }


def mlp_prediction(
    state: dict[str, np.ndarray],
    inputs: np.ndarray,
    digits: int,
    device: torch.device,
) -> np.ndarray:
    model = AntisymmetricPatternMlp(digits).to(device)
    model.load_state_dict(
        {
            name: torch.as_tensor(value, device=device)
            for name, value in state.items()
        }
    )
    model.eval()
    with torch.no_grad():
        return model(torch.as_tensor(inputs, device=device)).cpu().numpy()


def table_prediction(
    table: np.ndarray,
    encoded: np.ndarray,
    swap_map: np.ndarray,
) -> np.ndarray:
    return 0.5 * (
        table[encoded] - table[swap_map[encoded]]
    ).sum(axis=1)


def audit_candidate(
    candidate: CandidatePattern,
    encoded: dict[str, np.ndarray],
    targets: dict[str, np.ndarray],
    weights: dict[str, np.ndarray],
    phases: dict[str, np.ndarray],
    args: argparse.Namespace,
    device: torch.device,
) -> dict[str, Any]:
    states = 3**candidate.digits
    swap_map = color_swap_map(candidate.digits)
    decoded = (
        {
            split: decode_pattern_inputs(values, candidate.digits)
            for split, values in encoded.items()
        }
        if args.audit_model == "mlp"
        else None
    )
    phase_results = []
    test_prediction = np.zeros_like(targets["test"])
    for phase in range(PHASE_COUNT):
        train_selected = phases["train"] == phase
        validation_selected = phases["validation"] == phase
        test_selected = phases["test"] == phase
        if args.audit_model == "table":
            table, selection = train_table_phase(
                encoded["train"][train_selected],
                encoded["validation"][validation_selected],
                targets["train"][train_selected],
                targets["validation"][validation_selected],
                weights["train"][train_selected],
                weights["validation"][validation_selected],
                states,
                swap_map,
                args,
                device,
            )
            prediction = table_prediction(
                table,
                encoded["test"][test_selected],
                swap_map,
            )
        else:
            if decoded is None:
                raise AssertionError("decoded MLP inputs are missing")
            state, selection = train_mlp_phase(
                decoded["train"][train_selected],
                decoded["validation"][validation_selected],
                targets["train"][train_selected],
                targets["validation"][validation_selected],
                weights["train"][train_selected],
                weights["validation"][validation_selected],
                candidate.digits,
                args,
                device,
            )
            prediction = mlp_prediction(
                state,
                decoded["test"][test_selected],
                candidate.digits,
                device,
            )
        test_prediction[test_selected] = prediction
        baseline = weighted_metrics(
            targets["test"][test_selected],
            weights["test"][test_selected],
        )
        corrected = weighted_metrics(
            targets["test"][test_selected] - prediction,
            weights["test"][test_selected],
        )
        phase_results.append(
            {
                "phase": phase,
                "samples": int(np.count_nonzero(test_selected)),
                "selection": selection,
                "baseline": baseline,
                "corrected": corrected,
                "mse_reduction": (
                    (baseline["mse"] - corrected["mse"])
                    / baseline["mse"]
                ),
                "mae_reduction": (
                    (baseline["mae"] - corrected["mae"])
                    / baseline["mae"]
                ),
                "correction_rms": float(np.sqrt(np.mean(prediction**2))),
            }
        )
    baseline = weighted_metrics(targets["test"], weights["test"])
    corrected = weighted_metrics(
        targets["test"] - test_prediction,
        weights["test"],
    )
    return {
        "name": candidate.name,
        "digits": candidate.digits,
        "instances": len(candidate.patterns),
        "states": states,
        "baseline": baseline,
        "corrected": corrected,
        "mse_reduction": (
            (baseline["mse"] - corrected["mse"]) / baseline["mse"]
        ),
        "mae_reduction": (
            (baseline["mae"] - corrected["mae"]) / baseline["mae"]
        ),
        "phases": phase_results,
    }


def main() -> int:
    args = parse_args()
    try:
        if args.epochs < 1 or args.patience < 1:
            raise ValueError("epochs and patience must be positive")
        if args.learning_rate <= 0.0 or args.l2 < 0.0:
            raise ValueError("invalid optimization parameters")
        if args.occurrence_weight_cap < 1.0:
            raise ValueError("occurrence weight cap must be at least 1")
        names = tuple(
            name.strip() for name in args.patterns.split(",") if name.strip()
        )
        unknown = set(names) - set(CANDIDATE_PATTERNS)
        if unknown:
            raise ValueError(f"unknown patterns: {sorted(unknown)}")
        device = resolve_device(args.device)
        torch.manual_seed(args.seed)
        if device.type == "cuda":
            torch.cuda.manual_seed_all(args.seed)
        print(f"pattern residual audit device: {device}")

        data = {
            split: load_split(args.dataset_dir / f"{split}.npz")
            for split in ("train", "validation", "test")
        }
        static_model = read_java_model(args.static_model)
        for split, split_data in data.items():
            evaluated = np.rint(
                evaluate_java_model(static_model, split_data)
                * static_model.score_scale
            ).astype(np.int32)
            if not np.array_equal(evaluated, split_data["static_score"]):
                mismatch = int(
                    np.count_nonzero(evaluated != split_data["static_score"])
                )
                raise ValueError(
                    f"{split}: static model mismatch in {mismatch} samples"
                )

        base_model = read_java_model(args.base_model)
        targets = {
            split: residual_targets(base_model, split_data)
            for split, split_data in data.items()
        }
        weights = {
            split: sample_weights(split_data, args.occurrence_weight_cap)
            for split, split_data in data.items()
        }
        phases = {
            split: split_data["phase"].astype(np.int8)
            for split, split_data in data.items()
        }

        results = []
        for name in names:
            candidate = CANDIDATE_PATTERNS[name]
            print(
                f"audit {name}: digits={candidate.digits}, "
                f"instances={len(candidate.patterns)}"
            )
            encoded = {
                split: encode_patterns(split_data, candidate)
                for split, split_data in data.items()
            }
            result = audit_candidate(
                candidate,
                encoded,
                targets,
                weights,
                phases,
                args,
                device,
            )
            results.append(result)
            print(
                f"audit {name}: test MSE reduction "
                f"{result['mse_reduction'] * 100.0:.3f}%"
            )

        output = {
            "experiment": "EVAL-014",
            "dataset_dir": str(args.dataset_dir),
            "base_model": str(args.base_model),
            "static_model": str(args.static_model),
            "device": str(device),
            "arguments": {
                "audit_model": args.audit_model,
                "epochs": args.epochs,
                "patience": args.patience,
                "learning_rate": args.learning_rate,
                "output_anchors": args.output_anchors,
                "l2": args.l2,
                "occurrence_weight_cap": args.occurrence_weight_cap,
                "seed": args.seed,
            },
            "test_baseline": weighted_metrics(
                targets["test"],
                weights["test"],
            ),
            "results": results,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(output, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print(f"pattern residual audit written: {args.output}")
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
