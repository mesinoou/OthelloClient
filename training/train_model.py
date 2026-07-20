"""Train the article-style pattern network using NumPy only."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import sys

import numpy as np

from training.patterns import PATTERN_GROUPS


BRANCH_NAMES = ("diagonal", "edge2x", "corner")
PATTERN_SIZES = {
    name: len(PATTERN_GROUPS[name][0]) for name in BRANCH_NAMES
}
DEFAULT_PHASE_STARTS = (20, 30, 40, 50)
DEFAULT_SCORE_SCALE = 6400


def leaky_relu(values: np.ndarray) -> np.ndarray:
    return np.where(values >= 0.0, values, values * 0.01)


def leaky_relu_gradient(values: np.ndarray) -> np.ndarray:
    return np.where(values >= 0.0, 1.0, 0.01).astype(np.float32)


def decode_patterns(indices: np.ndarray, size: int) -> np.ndarray:
    flattened = indices.reshape(-1).astype(np.int64, copy=False)
    powers = (3 ** np.arange(size - 1, -1, -1)).astype(np.int64)
    digits = (flattened[:, None] // powers[None, :]) % 3
    return np.concatenate((digits == 1, digits == 2), axis=1).astype(
        np.float32
    )


def additional_inputs(data: dict[str, np.ndarray], indices: np.ndarray) -> np.ndarray:
    return np.column_stack(
        (
            data["mobility"][indices].astype(np.float32) / 30.0,
            (data["frontier_black"][indices].astype(np.float32) - 15.0)
            / 15.0,
            (data["frontier_white"][indices].astype(np.float32) - 15.0)
            / 15.0,
        )
    ).astype(np.float32, copy=False)


class PatternModel:
    def __init__(self, rng: np.random.Generator) -> None:
        self.parameters: dict[str, np.ndarray] = {}
        for name in BRANCH_NAMES:
            width = PATTERN_SIZES[name] * 2
            self.parameters[f"{name}_w1"] = self._weight(rng, width, 16)
            self.parameters[f"{name}_b1"] = np.zeros(16, dtype=np.float32)
            self.parameters[f"{name}_w2"] = self._weight(rng, 16, 16)
            self.parameters[f"{name}_b2"] = np.zeros(16, dtype=np.float32)
            self.parameters[f"{name}_w3"] = self._weight(rng, 16, 1)
            self.parameters[f"{name}_b3"] = np.zeros(1, dtype=np.float32)

        self.parameters["add_w1"] = self._weight(rng, 3, 8)
        self.parameters["add_b1"] = np.zeros(8, dtype=np.float32)
        self.parameters["add_w2"] = self._weight(rng, 8, 1)
        self.parameters["add_b2"] = np.zeros(1, dtype=np.float32)
        self.parameters["final_w"] = np.ones(4, dtype=np.float32) * 0.25
        self.parameters["final_b"] = np.zeros(1, dtype=np.float32)

    @staticmethod
    def _weight(
        rng: np.random.Generator,
        input_size: int,
        output_size: int,
    ) -> np.ndarray:
        scale = np.sqrt(2.0 / input_size)
        return rng.normal(
            0.0,
            scale,
            size=(input_size, output_size),
        ).astype(np.float32)

    def snapshot(self) -> dict[str, np.ndarray]:
        return {name: value.copy() for name, value in self.parameters.items()}

    def restore(self, snapshot: dict[str, np.ndarray]) -> None:
        for name, value in snapshot.items():
            self.parameters[name][...] = value

    def forward(
        self,
        data: dict[str, np.ndarray],
        indices: np.ndarray,
        cache: bool,
    ) -> tuple[np.ndarray, dict[str, object] | None]:
        branch_outputs = []
        branch_caches = {}
        for name in BRANCH_NAMES:
            output, branch_cache = self.forward_branch(
                name,
                data[name][indices],
                cache,
            )
            branch_outputs.append(output)
            if cache:
                branch_caches[name] = branch_cache

        add_output, add_cache = self.forward_additional(
            additional_inputs(data, indices),
            cache,
        )
        features = np.concatenate((*branch_outputs, add_output), axis=1)
        prediction = (
            features @ self.parameters["final_w"][:, None]
            + self.parameters["final_b"]
        )
        if not cache:
            return prediction, None
        return prediction, {
            "features": features,
            "branches": branch_caches,
            "additional": add_cache,
        }

    def forward_branch(
        self,
        name: str,
        indices: np.ndarray,
        cache: bool,
    ) -> tuple[np.ndarray, dict[str, np.ndarray | int] | None]:
        batch_size, instances = indices.shape
        inputs = decode_patterns(indices, PATTERN_SIZES[name])
        z1 = inputs @ self.parameters[f"{name}_w1"] + self.parameters[
            f"{name}_b1"
        ]
        a1 = leaky_relu(z1)
        z2 = a1 @ self.parameters[f"{name}_w2"] + self.parameters[
            f"{name}_b2"
        ]
        a2 = leaky_relu(z2)
        z3 = a2 @ self.parameters[f"{name}_w3"] + self.parameters[
            f"{name}_b3"
        ]
        a3 = leaky_relu(z3)
        output = a3.reshape(batch_size, instances).sum(axis=1, keepdims=True)
        if not cache:
            return output, None
        return output, {
            "inputs": inputs,
            "z1": z1,
            "a1": a1,
            "z2": z2,
            "a2": a2,
            "z3": z3,
            "instances": instances,
        }

    def forward_additional(
        self,
        inputs: np.ndarray,
        cache: bool,
    ) -> tuple[np.ndarray, dict[str, np.ndarray] | None]:
        z1 = inputs @ self.parameters["add_w1"] + self.parameters["add_b1"]
        a1 = leaky_relu(z1)
        z2 = a1 @ self.parameters["add_w2"] + self.parameters["add_b2"]
        output = leaky_relu(z2)
        if not cache:
            return output, None
        return output, {"inputs": inputs, "z1": z1, "a1": a1, "z2": z2}

    def backward(
        self,
        output_gradient: np.ndarray,
        cache: dict[str, object],
        l2: float,
    ) -> dict[str, np.ndarray]:
        features = cache["features"]
        gradients: dict[str, np.ndarray] = {
            "final_w": (features.T @ output_gradient).reshape(-1),
            "final_b": output_gradient.sum(axis=0),
        }
        feature_gradient = (
            output_gradient @ self.parameters["final_w"][None, :]
        )

        branch_caches = cache["branches"]
        for branch_index, name in enumerate(BRANCH_NAMES):
            gradients.update(
                self.backward_branch(
                    name,
                    feature_gradient[:, branch_index : branch_index + 1],
                    branch_caches[name],
                )
            )
        gradients.update(
            self.backward_additional(
                feature_gradient[:, 3:4],
                cache["additional"],
            )
        )

        if l2 > 0.0:
            for name, parameter in self.parameters.items():
                if "_w" in name:
                    gradients[name] += l2 * parameter
        return gradients

    def backward_branch(
        self,
        name: str,
        summed_gradient: np.ndarray,
        cache: dict[str, np.ndarray | int],
    ) -> dict[str, np.ndarray]:
        instances = int(cache["instances"])
        gradient = np.repeat(summed_gradient, instances, axis=0)
        z3_gradient = gradient * leaky_relu_gradient(cache["z3"])
        w3_gradient = cache["a2"].T @ z3_gradient
        b3_gradient = z3_gradient.sum(axis=0)

        a2_gradient = z3_gradient @ self.parameters[f"{name}_w3"].T
        z2_gradient = a2_gradient * leaky_relu_gradient(cache["z2"])
        w2_gradient = cache["a1"].T @ z2_gradient
        b2_gradient = z2_gradient.sum(axis=0)

        a1_gradient = z2_gradient @ self.parameters[f"{name}_w2"].T
        z1_gradient = a1_gradient * leaky_relu_gradient(cache["z1"])
        return {
            f"{name}_w1": cache["inputs"].T @ z1_gradient,
            f"{name}_b1": z1_gradient.sum(axis=0),
            f"{name}_w2": w2_gradient,
            f"{name}_b2": b2_gradient,
            f"{name}_w3": w3_gradient,
            f"{name}_b3": b3_gradient,
        }

    def backward_additional(
        self,
        output_gradient: np.ndarray,
        cache: dict[str, np.ndarray],
    ) -> dict[str, np.ndarray]:
        z2_gradient = output_gradient * leaky_relu_gradient(cache["z2"])
        a1_gradient = z2_gradient @ self.parameters["add_w2"].T
        z1_gradient = a1_gradient * leaky_relu_gradient(cache["z1"])
        return {
            "add_w1": cache["inputs"].T @ z1_gradient,
            "add_b1": z1_gradient.sum(axis=0),
            "add_w2": cache["a1"].T @ z2_gradient,
            "add_b2": z2_gradient.sum(axis=0),
        }


class Adam:
    def __init__(
        self,
        parameters: dict[str, np.ndarray],
        learning_rate: float,
    ) -> None:
        self.learning_rate = learning_rate
        self.step_count = 0
        self.first = {
            name: np.zeros_like(value) for name, value in parameters.items()
        }
        self.second = {
            name: np.zeros_like(value) for name, value in parameters.items()
        }

    def step(
        self,
        parameters: dict[str, np.ndarray],
        gradients: dict[str, np.ndarray],
    ) -> None:
        self.step_count += 1
        correction1 = 1.0 - 0.9**self.step_count
        correction2 = 1.0 - 0.999**self.step_count
        for name, parameter in parameters.items():
            gradient = gradients[name].astype(np.float32, copy=False)
            self.first[name] *= 0.9
            self.first[name] += 0.1 * gradient
            self.second[name] *= 0.999
            self.second[name] += 0.001 * gradient * gradient
            first_hat = self.first[name] / correction1
            second_hat = self.second[name] / correction2
            parameter -= self.learning_rate * first_hat / (
                np.sqrt(second_hat) + 1.0e-7
            )


@dataclass(frozen=True)
class Metrics:
    mse: float
    mae: float


def load_dataset(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        return {name: archive[name] for name in archive.files}


def phase_ids(ply: np.ndarray, phase_starts: tuple[int, ...]) -> np.ndarray:
    return np.searchsorted(
        np.asarray(phase_starts[1:], dtype=np.int16),
        ply.astype(np.int16),
        side="right",
    ).astype(np.int8)


def choose_indices(
    data: dict[str, np.ndarray],
    phase: int,
    phase_starts: tuple[int, ...],
    maximum: int | None,
    rng: np.random.Generator,
) -> np.ndarray:
    indices = np.flatnonzero(phase_ids(data["ply"], phase_starts) == phase)
    if maximum is not None and len(indices) > maximum:
        indices = np.sort(rng.choice(indices, size=maximum, replace=False))
    return indices


def labels(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    label_name: str,
) -> np.ndarray:
    key = "label_filled" if label_name == "filled" else "label_disc"
    return data[key][indices].astype(np.float32)[:, None] / 64.0


def evaluate(
    model: PatternModel,
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    label_name: str,
    batch_size: int,
) -> Metrics:
    squared_error = 0.0
    absolute_error = 0.0
    count = 0
    for start in range(0, len(indices), batch_size):
        batch = indices[start : start + batch_size]
        prediction, _ = model.forward(data, batch, cache=False)
        difference = prediction - labels(data, batch, label_name)
        squared_error += float(np.square(difference).sum())
        absolute_error += float(np.abs(difference).sum())
        count += len(batch)
    return Metrics(squared_error / count, absolute_error / count)


def train_phase(
    phase: int,
    train: dict[str, np.ndarray],
    validation: dict[str, np.ndarray],
    train_indices: np.ndarray,
    validation_indices: np.ndarray,
    args: argparse.Namespace,
    rng: np.random.Generator,
) -> tuple[PatternModel, list[dict[str, float]]]:
    if len(train_indices) == 0 or len(validation_indices) == 0:
        raise ValueError(f"phase {phase} has no train or validation samples")
    model = PatternModel(rng)
    optimizer = Adam(model.parameters, args.learning_rate)
    history = []
    best_snapshot = model.snapshot()
    best_loss = float("inf")
    stale_epochs = 0

    for epoch in range(1, args.epochs + 1):
        shuffled = train_indices.copy()
        rng.shuffle(shuffled)
        for start in range(0, len(shuffled), args.batch_size):
            batch = shuffled[start : start + args.batch_size]
            prediction, cache = model.forward(train, batch, cache=True)
            difference = prediction - labels(train, batch, args.label)
            output_gradient = (2.0 / len(batch)) * difference
            gradients = model.backward(output_gradient, cache, args.l2)
            optimizer.step(model.parameters, gradients)

        train_metrics = evaluate(
            model,
            train,
            train_indices,
            args.label,
            args.batch_size,
        )
        validation_metrics = evaluate(
            model,
            validation,
            validation_indices,
            args.label,
            args.batch_size,
        )
        epoch_result = {
            "epoch": epoch,
            "train_mse": train_metrics.mse,
            "train_mae": train_metrics.mae,
            "validation_mse": validation_metrics.mse,
            "validation_mae": validation_metrics.mae,
        }
        history.append(epoch_result)
        print(f"phase {phase}: {json.dumps(epoch_result)}")

        if validation_metrics.mse < best_loss - 1.0e-7:
            best_loss = validation_metrics.mse
            best_snapshot = model.snapshot()
            stale_epochs = 0
        else:
            stale_epochs += 1
            if stale_epochs >= args.patience:
                print(f"phase {phase}: early stop")
                break

    model.restore(best_snapshot)
    return model, history


def save_float_models(
    path: Path,
    models: list[PatternModel],
    phase_starts: tuple[int, ...],
    score_scale: int,
) -> None:
    payload: dict[str, np.ndarray] = {
        "phase_starts": np.asarray(phase_starts, dtype=np.int16),
        "score_scale": np.asarray([score_scale], dtype=np.int32),
    }
    for phase, model in enumerate(models):
        for name, value in model.parameters.items():
            payload[f"phase{phase}_{name}"] = value
    np.savez_compressed(path, **payload)


def quantize(values: np.ndarray, scale: int) -> tuple[np.ndarray, int]:
    rounded = np.rint(values * scale)
    clipped_count = int(np.count_nonzero((rounded < -32768) | (rounded > 32767)))
    return np.clip(rounded, -32768, 32767).astype(np.int16), clipped_count


def export_tables(
    path: Path,
    models: list[PatternModel],
    phase_starts: tuple[int, ...],
    score_scale: int,
    batch_size: int,
) -> dict[str, int]:
    payload: dict[str, np.ndarray] = {
        "phase_starts": np.asarray(phase_starts, dtype=np.int16),
        "score_scale": np.asarray([score_scale], dtype=np.int32),
        "phase_bias": np.asarray(
            [round(float(model.parameters["final_b"][0]) * score_scale) for model in models],
            dtype=np.int32,
        ),
    }
    clipped = {}
    for phase, model in enumerate(models):
        for branch_index, name in enumerate(BRANCH_NAMES):
            count = 3 ** PATTERN_SIZES[name]
            values = np.empty(count, dtype=np.float32)
            for start in range(0, count, batch_size):
                stop = min(count, start + batch_size)
                pattern_indices = np.arange(start, stop, dtype=np.uint16)[:, None]
                output, _ = model.forward_branch(name, pattern_indices, cache=False)
                values[start:stop] = output[:, 0]
            weighted = values * model.parameters["final_w"][branch_index]
            table, clipped_count = quantize(weighted, score_scale)
            key = f"phase{phase}_{name}"
            payload[key] = table
            clipped[key] = clipped_count

        mobility = np.repeat(np.arange(-30, 31, dtype=np.int16), 65 * 65)
        black_frontier = np.tile(
            np.repeat(np.arange(65, dtype=np.int16), 65),
            61,
        )
        white_frontier = np.tile(np.arange(65, dtype=np.int16), 61 * 65)
        add_inputs = np.column_stack(
            (
                mobility.astype(np.float32) / 30.0,
                (black_frontier.astype(np.float32) - 15.0) / 15.0,
                (white_frontier.astype(np.float32) - 15.0) / 15.0,
            )
        )
        add_values = np.empty(len(add_inputs), dtype=np.float32)
        for start in range(0, len(add_inputs), batch_size):
            stop = min(len(add_inputs), start + batch_size)
            output, _ = model.forward_additional(add_inputs[start:stop], cache=False)
            add_values[start:stop] = output[:, 0]
        weighted_add = add_values * model.parameters["final_w"][3]
        add_table, clipped_count = quantize(weighted_add, score_scale)
        key = f"phase{phase}_additional"
        payload[key] = add_table.reshape(61, 65, 65)
        clipped[key] = clipped_count

    np.savez_compressed(path, **payload)
    return clipped


def evaluate_quantized(
    tables_path: Path,
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    phase: int,
    label_name: str,
) -> Metrics:
    with np.load(tables_path, allow_pickle=False) as tables:
        score = np.full(
            len(indices),
            int(tables["phase_bias"][phase]),
            dtype=np.int64,
        )
        for name in BRANCH_NAMES:
            table = tables[f"phase{phase}_{name}"]
            score += table[data[name][indices]].astype(np.int64).sum(axis=1)
        mobility = np.clip(data["mobility"][indices].astype(np.int16), -30, 30)
        black_frontier = np.clip(
            data["frontier_black"][indices].astype(np.int16),
            0,
            64,
        )
        white_frontier = np.clip(
            data["frontier_white"][indices].astype(np.int16),
            0,
            64,
        )
        score += tables[f"phase{phase}_additional"][
            mobility + 30,
            black_frontier,
            white_frontier,
        ].astype(np.int64)
        prediction = score.astype(np.float32) / float(tables["score_scale"][0])
    expected = labels(data, indices, label_name)[:, 0]
    difference = prediction - expected
    return Metrics(
        float(np.square(difference).mean()),
        float(np.abs(difference).mean()),
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train the pattern evaluator.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/nyanyan-self-play-v1"),
        help="input dataset directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/models/pattern-evaluation-v1"),
        help="model and lookup-table output directory",
    )
    parser.add_argument("--epochs", type=int, default=20, help="maximum epochs")
    parser.add_argument(
        "--batch-size",
        type=int,
        default=1024,
        help="positions per mini-batch",
    )
    parser.add_argument(
        "--learning-rate",
        type=float,
        default=0.001,
        help="Adam learning rate",
    )
    parser.add_argument(
        "--l2",
        type=float,
        default=1.0e-5,
        help="L2 regularization coefficient",
    )
    parser.add_argument(
        "--patience",
        type=int,
        default=5,
        help="epochs without validation improvement before stopping",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=20260720,
        help="weight initialization and shuffle seed",
    )
    parser.add_argument(
        "--label",
        choices=("filled", "disc"),
        default="filled",
        help="terminal score used as the training target",
    )
    parser.add_argument(
        "--phase-starts",
        default="20,30,40,50",
        help="comma-separated starts for the four phase models",
    )
    parser.add_argument(
        "--score-scale",
        type=int,
        default=DEFAULT_SCORE_SCALE,
        help="integer lookup-table score scale",
    )
    parser.add_argument(
        "--max-samples-per-phase",
        type=int,
        default=None,
        help="limit each split and phase; marks output as smoke-only",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace the output directory if it exists",
    )
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> tuple[int, ...]:
    phase_starts = tuple(int(value) for value in args.phase_starts.split(","))
    if len(phase_starts) != 4 or tuple(sorted(phase_starts)) != phase_starts:
        raise ValueError("phase-starts must be four ascending integers")
    if args.epochs < 1 or args.batch_size < 1 or args.patience < 1:
        raise ValueError("epochs, batch-size, and patience must be positive")
    if args.learning_rate <= 0.0 or args.l2 < 0.0:
        raise ValueError("learning-rate must be positive and l2 non-negative")
    if args.score_scale < 1:
        raise ValueError("score-scale must be positive")
    if args.max_samples_per_phase is not None and args.max_samples_per_phase < 1:
        raise ValueError("max-samples-per-phase must be positive")
    return phase_starts


def main() -> int:
    args = parse_args()
    try:
        phase_starts = validate_args(args)
        if args.output_dir.exists():
            if not args.overwrite:
                raise FileExistsError(
                    f"output directory exists; pass --overwrite: {args.output_dir}"
                )
            shutil.rmtree(args.output_dir)
        args.output_dir.mkdir(parents=True)

        train = load_dataset(args.dataset_dir / "train.npz")
        validation = load_dataset(args.dataset_dir / "validation.npz")
        test = load_dataset(args.dataset_dir / "test.npz")
        rng = np.random.default_rng(args.seed)

        models = []
        histories = []
        selections = []
        for phase in range(len(phase_starts)):
            train_indices = choose_indices(
                train,
                phase,
                phase_starts,
                args.max_samples_per_phase,
                rng,
            )
            validation_indices = choose_indices(
                validation,
                phase,
                phase_starts,
                args.max_samples_per_phase,
                rng,
            )
            model, history = train_phase(
                phase,
                train,
                validation,
                train_indices,
                validation_indices,
                args,
                rng,
            )
            models.append(model)
            histories.append(history)
            selections.append(
                {
                    "phase": phase,
                    "train": len(train_indices),
                    "validation": len(validation_indices),
                }
            )

        model_path = args.output_dir / "model-float.npz"
        tables_path = args.output_dir / "evaluation-tables.npz"
        save_float_models(model_path, models, phase_starts, args.score_scale)
        clipped = export_tables(
            tables_path,
            models,
            phase_starts,
            args.score_scale,
            args.batch_size,
        )

        test_results = []
        for phase in range(len(phase_starts)):
            test_indices = choose_indices(
                test,
                phase,
                phase_starts,
                args.max_samples_per_phase,
                rng,
            )
            float_metrics = evaluate(
                models[phase],
                test,
                test_indices,
                args.label,
                args.batch_size,
            )
            quantized_metrics = evaluate_quantized(
                tables_path,
                test,
                test_indices,
                phase,
                args.label,
            )
            test_results.append(
                {
                    "phase": phase,
                    "samples": len(test_indices),
                    "float_mse": float_metrics.mse,
                    "float_mae": float_metrics.mae,
                    "quantized_mse": quantized_metrics.mse,
                    "quantized_mae": quantized_metrics.mae,
                }
            )

        metadata = {
            "model_format": 1,
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "architecture": {
                "pattern_branches": {
                    name: [PATTERN_SIZES[name] * 2, 16, 16, 1]
                    for name in BRANCH_NAMES
                },
                "additional_branch": [3, 8, 1],
                "activation": "LeakyReLU(alpha=0.01)",
                "combiner": "linear",
            },
            "training": {
                "optimizer": "Adam",
                "loss": "mean squared error",
                "label": args.label,
                "seed": args.seed,
                "phase_starts": phase_starts,
                "batch_size": args.batch_size,
                "learning_rate": args.learning_rate,
                "l2": args.l2,
                "max_samples_per_phase": args.max_samples_per_phase,
                "selections": selections,
                "history": histories,
            },
            "quantization": {
                "dtype": "int16",
                "score_scale": args.score_scale,
                "clipped_entries": clipped,
            },
            "test": test_results,
            "smoke_only": args.max_samples_per_phase is not None,
        }
        (args.output_dir / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(test_results, indent=2))
        print(f"model written to {args.output_dir}")
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
