"""Train and export the phase-aware additive pattern evaluator."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import shutil
import sys
import time
from typing import Any, TextIO

import numpy as np

from training.patterns import PATTERN_GROUPS, PatternGroup
from training.export_java_model import export_java_model

try:
    import torch
    from torch import nn
except ImportError:  # Optional CUDA backend.
    torch = None
    nn = None


BRANCH_NAMES = tuple(PATTERN_GROUPS)
PAIR_BRANCHES = {
    "mobility": (
        ("mobility_own", 0.0, 32.0),
        ("mobility_opponent", 0.0, 32.0),
    ),
    "frontier": (
        ("frontier_own", 15.0, 15.0),
        ("frontier_opponent", 15.0, 15.0),
    ),
}
SCALAR_BRANCHES = {
    "disc_difference": (-64, 64, 64.0),
    "corner_difference": (-4, 4, 4.0),
    "corner_move_difference": (-4, 4, 4.0),
    "stable_edge_difference": (-28, 28, 28.0),
    "parity_access_difference": (-32, 32, 32.0),
}
AUXILIARY_BRANCH_NAMES = (*PAIR_BRANCHES, *SCALAR_BRANCHES)
DEFAULT_PHASE_STARTS = (20, 30, 40, 50)
DEFAULT_SCORE_SCALE = 6400
REQUIRED_ARRAYS = {
    "player",
    "ply",
    "label_disc",
    "label_filled",
    *BRANCH_NAMES,
    *(field for specs in PAIR_BRANCHES.values() for field, _, _ in specs),
    *SCALAR_BRANCHES,
}


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


def swap_pattern_colors(indices: np.ndarray, size: int) -> np.ndarray:
    flattened = indices.reshape(-1).astype(np.int64, copy=False)
    powers = (3 ** np.arange(size - 1, -1, -1)).astype(np.int64)
    digits = (flattened[:, None] // powers[None, :]) % 3
    swapped = np.where(digits == 1, 2, np.where(digits == 2, 1, 0))
    return (swapped * powers[None, :]).sum(axis=1).reshape(indices.shape)


def pattern_inputs(
    group: PatternGroup,
    indices: np.ndarray,
    class_ids: np.ndarray | None = None,
) -> np.ndarray:
    inputs = decode_patterns(indices, group.max_squares)
    if group.class_count == 0:
        return inputs

    batch_size, instances = indices.shape
    if class_ids is None:
        classes = np.tile(
            np.asarray(group.class_ids, dtype=np.int64),
            batch_size,
        )
    else:
        classes = class_ids.reshape(-1).astype(np.int64, copy=False)
        if len(classes) != batch_size * instances:
            raise ValueError("pattern class count differs from pattern inputs")
    class_inputs = np.zeros(
        (len(classes), group.class_count),
        dtype=np.float32,
    )
    class_inputs[np.arange(len(classes)), classes] = 1.0
    return np.concatenate((inputs, class_inputs), axis=1)


def auxiliary_inputs(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    name: str,
) -> np.ndarray:
    if name in PAIR_BRANCHES:
        columns = [
            (data[field][indices].astype(np.float32) - center) / scale
            for field, center, scale in PAIR_BRANCHES[name]
        ]
        return np.column_stack(columns).astype(np.float32, copy=False)
    _, _, scale = SCALAR_BRANCHES[name]
    return (data[name][indices].astype(np.float32) / scale)[:, None]


def _branch_layer_sizes(name: str) -> tuple[int, ...]:
    if name in PATTERN_GROUPS:
        return (PATTERN_GROUPS[name].input_width, 16, 16, 1)
    if name in PAIR_BRANCHES:
        return (2, 8, 1)
    return (1, 4, 1)


class PatternModel:
    def __init__(self, rng: np.random.Generator) -> None:
        self.parameters: dict[str, np.ndarray] = {}
        for name in (*BRANCH_NAMES, *AUXILIARY_BRANCH_NAMES):
            sizes = _branch_layer_sizes(name)
            for layer, (input_size, output_size) in enumerate(
                zip(sizes, sizes[1:]),
                start=1,
            ):
                weights = self._weight(
                    rng,
                    input_size,
                    output_size,
                )
                if layer == len(sizes) - 1:
                    weights *= 0.05
                self.parameters[f"{name}_w{layer}"] = weights
                self.parameters[f"{name}_b{layer}"] = np.zeros(
                    output_size,
                    dtype=np.float32,
                )
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
        prediction = np.zeros((len(indices), 1), dtype=np.float32)
        pattern_caches = {}
        auxiliary_caches = {}
        for name in BRANCH_NAMES:
            output, branch_cache = self.forward_pattern(
                name,
                data[name][indices],
                cache,
            )
            prediction += output
            if cache:
                pattern_caches[name] = branch_cache
        for name in AUXILIARY_BRANCH_NAMES:
            inputs = auxiliary_inputs(data, indices, name)
            output, branch_cache = self.forward_auxiliary(name, inputs, cache)
            prediction += output
            if cache:
                auxiliary_caches[name] = branch_cache
        if not cache:
            return prediction, None
        return prediction, {
            "patterns": pattern_caches,
            "auxiliary": auxiliary_caches,
        }

    def forward_pattern(
        self,
        name: str,
        indices: np.ndarray,
        cache: bool,
        class_ids: np.ndarray | None = None,
    ) -> tuple[np.ndarray, dict[str, object] | None]:
        batch_size, instances = indices.shape
        group = PATTERN_GROUPS[name]
        inputs = pattern_inputs(group, indices, class_ids)
        swapped_inputs = pattern_inputs(
            group,
            swap_pattern_colors(indices, group.max_squares),
            class_ids,
        )
        values, dense_cache = self.forward_dense(name, inputs, cache)
        swapped_values, swapped_cache = self.forward_dense(
            name,
            swapped_inputs,
            cache,
        )
        antisymmetric = 0.5 * (values - swapped_values)
        output = antisymmetric.reshape(batch_size, instances).sum(
            axis=1,
            keepdims=True,
        )
        if not cache:
            return output, None
        return output, {
            "dense": dense_cache,
            "swapped_dense": swapped_cache,
            "instances": instances,
        }

    def forward_auxiliary(
        self,
        name: str,
        inputs: np.ndarray,
        cache: bool,
    ) -> tuple[np.ndarray, dict[str, object] | None]:
        swapped_inputs = inputs[:, ::-1] if name in PAIR_BRANCHES else -inputs
        values, dense_cache = self.forward_dense(name, inputs, cache)
        swapped_values, swapped_cache = self.forward_dense(
            name,
            swapped_inputs,
            cache,
        )
        output = 0.5 * (values - swapped_values)
        if not cache:
            return output, None
        return output, {"dense": dense_cache, "swapped_dense": swapped_cache}

    def forward_dense(
        self,
        name: str,
        inputs: np.ndarray,
        cache: bool,
    ) -> tuple[np.ndarray, dict[str, object] | None]:
        activations = [inputs]
        preactivations = []
        layer_count = len(_branch_layer_sizes(name)) - 1
        values = inputs
        for layer in range(1, layer_count + 1):
            values = (
                values @ self.parameters[f"{name}_w{layer}"]
                + self.parameters[f"{name}_b{layer}"]
            )
            preactivations.append(values)
            if layer != layer_count:
                values = leaky_relu(values)
            activations.append(values)
        if not cache:
            return values, None
        return values, {
            "activations": activations,
            "preactivations": preactivations,
        }

    def backward(
        self,
        output_gradient: np.ndarray,
        cache: dict[str, object],
        l2: float,
    ) -> dict[str, np.ndarray]:
        gradients: dict[str, np.ndarray] = {}
        for name in BRANCH_NAMES:
            branch_cache = cache["patterns"][name]
            instances = int(branch_cache["instances"])
            repeated = np.repeat(output_gradient, instances, axis=0)
            original = self.backward_dense(
                name,
                repeated * 0.5,
                branch_cache["dense"],
            )
            swapped = self.backward_dense(
                name,
                repeated * -0.5,
                branch_cache["swapped_dense"],
            )
            gradients.update(_sum_gradients(original, swapped))
        for name in AUXILIARY_BRANCH_NAMES:
            branch_cache = cache["auxiliary"][name]
            original = self.backward_dense(
                name,
                output_gradient * 0.5,
                branch_cache["dense"],
            )
            swapped = self.backward_dense(
                name,
                output_gradient * -0.5,
                branch_cache["swapped_dense"],
            )
            gradients.update(_sum_gradients(original, swapped))
        if l2 > 0.0:
            for name, parameter in self.parameters.items():
                if "_w" in name:
                    gradients[name] += l2 * parameter
        return gradients

    def backward_dense(
        self,
        name: str,
        output_gradient: np.ndarray,
        cache: dict[str, object],
    ) -> dict[str, np.ndarray]:
        activations = cache["activations"]
        preactivations = cache["preactivations"]
        layer_count = len(preactivations)
        gradient = output_gradient
        gradients = {}
        for layer in range(layer_count, 0, -1):
            if layer != layer_count:
                gradient = gradient * leaky_relu_gradient(
                    preactivations[layer - 1]
                )
            gradients[f"{name}_w{layer}"] = (
                activations[layer - 1].T @ gradient
            )
            gradients[f"{name}_b{layer}"] = gradient.sum(axis=0)
            gradient = gradient @ self.parameters[f"{name}_w{layer}"].T
        return gradients


def _sum_gradients(
    first: dict[str, np.ndarray],
    second: dict[str, np.ndarray],
) -> dict[str, np.ndarray]:
    return {name: value + second[name] for name, value in first.items()}


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


class ProgressBar:
    def __init__(
        self,
        label: str,
        total: int,
        enabled: bool,
        stream: TextIO = sys.stdout,
    ) -> None:
        self.label = label
        self.total = max(total, 1)
        self.enabled = enabled
        self.stream = stream
        self.started = time.monotonic()
        self.last_update = 0.0

    def update(self, completed: int, force: bool = False) -> None:
        if not self.enabled:
            return
        now = time.monotonic()
        completed = min(completed, self.total)
        if completed < self.total and not force and now - self.last_update < 0.2:
            return
        elapsed = max(now - self.started, 1.0e-9)
        fraction = completed / self.total
        width = 24
        filled = min(width, int(fraction * width))
        bar = "#" * filled + "-" * (width - filled)
        rate = completed / elapsed
        eta = (self.total - completed) / rate if rate > 0.0 else 0.0
        print(
            f"\r{self.label} [{bar}] {fraction:6.1%} "
            f"{completed}/{self.total} ETA {eta:6.1f}s",
            end="\n" if completed >= self.total else "",
            file=self.stream,
            flush=True,
        )
        self.last_update = now


def load_dataset(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        missing = REQUIRED_ARRAYS - set(archive.files)
        if missing:
            raise ValueError(
                f"{path}: dataset format is incompatible; missing "
                f"{sorted(missing)}. Rebuild the v3 dataset."
            )
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
    result = data[key][indices].astype(np.float32)
    result *= data["player"][indices].astype(np.float32)
    return result[:, None] / 64.0


def evaluate(
    model: PatternModel,
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    label_name: str,
    batch_size: int,
    progress_label: str | None = None,
    progress_enabled: bool = False,
) -> Metrics:
    squared_error = 0.0
    absolute_error = 0.0
    count = 0
    batches = max(math.ceil(len(indices) / batch_size), 1)
    progress = ProgressBar(
        progress_label or "evaluate",
        batches,
        progress_enabled and progress_label is not None,
    )
    for batch_number, start in enumerate(
        range(0, len(indices), batch_size),
        start=1,
    ):
        batch = indices[start : start + batch_size]
        prediction, _ = model.forward(data, batch, cache=False)
        difference = prediction - labels(data, batch, label_name)
        squared_error += float(np.square(difference).sum())
        absolute_error += float(np.abs(difference).sum())
        count += len(batch)
        progress.update(batch_number)
    return Metrics(squared_error / count, absolute_error / count)


def train_phase_numpy(
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
        batches = math.ceil(len(shuffled) / args.batch_size)
        progress = ProgressBar(
            f"phase {phase} epoch {epoch}/{args.epochs} train",
            batches,
            not args.no_progress,
        )
        for batch_number, start in enumerate(
            range(0, len(shuffled), args.batch_size),
            start=1,
        ):
            batch = shuffled[start : start + args.batch_size]
            prediction, cache = model.forward(train, batch, cache=True)
            difference = prediction - labels(train, batch, args.label)
            output_gradient = (2.0 / len(batch)) * difference
            gradients = model.backward(output_gradient, cache, args.l2)
            optimizer.step(model.parameters, gradients)
            progress.update(batch_number)

        train_metrics = evaluate(
            model,
            train,
            train_indices,
            args.label,
            args.batch_size,
            f"phase {phase} epoch {epoch} train metrics",
            not args.no_progress,
        )
        validation_metrics = evaluate(
            model,
            validation,
            validation_indices,
            args.label,
            args.batch_size,
            f"phase {phase} epoch {epoch} validation",
            not args.no_progress,
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


if torch is not None:

    class TorchPatternModel(nn.Module):
        def __init__(self, template: PatternModel) -> None:
            super().__init__()
            self.values = nn.ParameterDict(
                {
                    name: nn.Parameter(torch.from_numpy(value.copy()))
                    for name, value in template.parameters.items()
                }
            )

        def forward_dense(self, name: str, inputs: Any) -> Any:
            values = inputs
            layer_count = len(_branch_layer_sizes(name)) - 1
            for layer in range(1, layer_count + 1):
                values = (
                    values @ self.values[f"{name}_w{layer}"]
                    + self.values[f"{name}_b{layer}"]
                )
                if layer != layer_count:
                    values = torch.where(values >= 0.0, values, values * 0.01)
            return values

        def forward(self, batch: dict[str, Any]) -> Any:
            count = len(batch["player"])
            prediction = torch.zeros(
                (count, 1),
                device=batch["player"].device,
                dtype=torch.float32,
            )
            for name, group in PATTERN_GROUPS.items():
                encoded = batch[name].long()
                inputs = torch_pattern_inputs(group, encoded)
                swapped_inputs = torch_pattern_inputs(
                    group,
                    torch_swap_pattern_colors(encoded, group.max_squares),
                )
                values = self.forward_dense(name, inputs)
                swapped_values = self.forward_dense(name, swapped_inputs)
                values = 0.5 * (values - swapped_values)
                prediction = prediction + values.reshape(
                    count,
                    group.instances,
                ).sum(dim=1, keepdim=True)
            for name in AUXILIARY_BRANCH_NAMES:
                inputs = torch_auxiliary_inputs(batch, name)
                swapped_inputs = (
                    torch.flip(inputs, dims=(1,))
                    if name in PAIR_BRANCHES
                    else -inputs
                )
                prediction = prediction + 0.5 * (
                    self.forward_dense(name, inputs)
                    - self.forward_dense(name, swapped_inputs)
                )
            return prediction


def torch_pattern_inputs(group: PatternGroup, indices: Any) -> Any:
    flattened = indices.reshape(-1).long()
    powers = torch.pow(
        torch.tensor(3, device=indices.device, dtype=torch.long),
        torch.arange(
            group.max_squares - 1,
            -1,
            -1,
            device=indices.device,
            dtype=torch.long,
        ),
    )
    digits = (flattened[:, None] // powers[None, :]) % 3
    inputs = torch.cat((digits == 1, digits == 2), dim=1).float()
    if group.class_count == 0:
        return inputs
    classes = torch.tensor(
        group.class_ids,
        device=indices.device,
        dtype=torch.long,
    ).repeat(indices.shape[0])
    class_inputs = torch.zeros(
        (len(classes), group.class_count),
        device=indices.device,
        dtype=torch.float32,
    )
    class_inputs.scatter_(1, classes[:, None], 1.0)
    return torch.cat((inputs, class_inputs), dim=1)


def torch_swap_pattern_colors(indices: Any, size: int) -> Any:
    flattened = indices.reshape(-1).long()
    powers = torch.pow(
        torch.tensor(3, device=indices.device, dtype=torch.long),
        torch.arange(
            size - 1,
            -1,
            -1,
            device=indices.device,
            dtype=torch.long,
        ),
    )
    digits = (flattened[:, None] // powers[None, :]) % 3
    swapped = torch.where(
        digits == 1,
        torch.full_like(digits, 2),
        torch.where(digits == 2, torch.ones_like(digits), digits),
    )
    return (swapped * powers[None, :]).sum(dim=1).reshape(indices.shape)


def torch_auxiliary_inputs(batch: dict[str, Any], name: str) -> Any:
    if name in PAIR_BRANCHES:
        columns = [
            (batch[field].float() - center) / scale
            for field, center, scale in PAIR_BRANCHES[name]
        ]
        return torch.stack(columns, dim=1)
    _, _, scale = SCALAR_BRANCHES[name]
    return (batch[name].float() / scale)[:, None]


def torch_batch(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    device: Any,
) -> dict[str, Any]:
    fields = REQUIRED_ARRAYS - {"ply", "label_disc", "label_filled"}
    fields |= {"label_disc", "label_filled"}
    return {
        name: torch.as_tensor(data[name][indices], device=device)
        for name in fields
    }


def torch_labels(batch: dict[str, Any], label_name: str) -> Any:
    key = "label_filled" if label_name == "filled" else "label_disc"
    return (
        batch[key].float() * batch["player"].float()
    )[:, None] / 64.0


def evaluate_torch(
    model: Any,
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    label_name: str,
    batch_size: int,
    device: Any,
    progress_label: str,
    progress_enabled: bool,
) -> Metrics:
    model.eval()
    squared_error = 0.0
    absolute_error = 0.0
    count = 0
    batches = max(math.ceil(len(indices) / batch_size), 1)
    progress = ProgressBar(progress_label, batches, progress_enabled)
    with torch.no_grad():
        for batch_number, start in enumerate(
            range(0, len(indices), batch_size),
            start=1,
        ):
            selected = indices[start : start + batch_size]
            batch = torch_batch(data, selected, device)
            difference = model(batch) - torch_labels(batch, label_name)
            squared_error += float(torch.square(difference).sum().item())
            absolute_error += float(torch.abs(difference).sum().item())
            count += len(selected)
            progress.update(batch_number)
    return Metrics(squared_error / count, absolute_error / count)


def train_phase_torch(
    phase: int,
    train: dict[str, np.ndarray],
    validation: dict[str, np.ndarray],
    train_indices: np.ndarray,
    validation_indices: np.ndarray,
    args: argparse.Namespace,
    rng: np.random.Generator,
    device: Any,
) -> tuple[PatternModel, list[dict[str, float]]]:
    if len(train_indices) == 0 or len(validation_indices) == 0:
        raise ValueError(f"phase {phase} has no train or validation samples")
    template = PatternModel(rng)
    model = TorchPatternModel(template).to(device)
    weight_parameters = [
        value for name, value in model.values.items() if "_w" in name
    ]
    other_parameters = [
        value for name, value in model.values.items() if "_w" not in name
    ]
    optimizer = torch.optim.Adam(
        (
            {"params": weight_parameters, "weight_decay": args.l2},
            {"params": other_parameters, "weight_decay": 0.0},
        ),
        lr=args.learning_rate,
    )
    use_amp = args.amp and device.type == "cuda"
    scaler = torch.cuda.amp.GradScaler(enabled=use_amp)
    history = []
    best_state = {
        name: value.detach().cpu().clone()
        for name, value in model.state_dict().items()
    }
    best_loss = float("inf")
    stale_epochs = 0

    for epoch in range(1, args.epochs + 1):
        shuffled = train_indices.copy()
        rng.shuffle(shuffled)
        batches = math.ceil(len(shuffled) / args.batch_size)
        progress = ProgressBar(
            f"phase {phase} epoch {epoch}/{args.epochs} CUDA train",
            batches,
            not args.no_progress,
        )
        model.train()
        for batch_number, start in enumerate(
            range(0, len(shuffled), args.batch_size),
            start=1,
        ):
            selected = shuffled[start : start + args.batch_size]
            batch = torch_batch(train, selected, device)
            optimizer.zero_grad(set_to_none=True)
            with torch.cuda.amp.autocast(enabled=use_amp):
                difference = model(batch) - torch_labels(batch, args.label)
                loss = torch.square(difference).mean()
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            progress.update(batch_number)

        train_metrics = evaluate_torch(
            model,
            train,
            train_indices,
            args.label,
            args.batch_size,
            device,
            f"phase {phase} epoch {epoch} train metrics",
            not args.no_progress,
        )
        validation_metrics = evaluate_torch(
            model,
            validation,
            validation_indices,
            args.label,
            args.batch_size,
            device,
            f"phase {phase} epoch {epoch} validation",
            not args.no_progress,
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
            best_state = {
                name: value.detach().cpu().clone()
                for name, value in model.state_dict().items()
            }
            stale_epochs = 0
        else:
            stale_epochs += 1
            if stale_epochs >= args.patience:
                print(f"phase {phase}: early stop")
                break

    model.load_state_dict(best_state)
    result = PatternModel(np.random.default_rng(0))
    for name, value in model.values.items():
        result.parameters[name][...] = value.detach().cpu().numpy()
    return result, history


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


def pattern_table_key(phase: int, name: str, class_id: int) -> str:
    group = PATTERN_GROUPS[name]
    if group.class_count == 0:
        return f"phase{phase}_{name}"
    return f"phase{phase}_{name}_{group.class_names[class_id]}"


def export_tables(
    path: Path,
    models: list[PatternModel],
    phase_starts: tuple[int, ...],
    score_scale: int,
    batch_size: int,
    progress_enabled: bool,
) -> dict[str, int]:
    payload: dict[str, np.ndarray] = {
        "phase_starts": np.asarray(phase_starts, dtype=np.int16),
        "score_scale": np.asarray([score_scale], dtype=np.int32),
        "phase_bias": np.zeros(len(models), dtype=np.int32),
    }
    tables_per_phase = sum(
        group.class_count or 1 for group in PATTERN_GROUPS.values()
    ) + len(AUXILIARY_BRANCH_NAMES)
    progress = ProgressBar(
        "export evaluation tables",
        len(models) * tables_per_phase,
        progress_enabled,
    )
    completed = 0
    clipped = {}
    for phase, model in enumerate(models):
        for name, group in PATTERN_GROUPS.items():
            for class_id in range(group.class_count or 1):
                size = group.size_for_class(class_id)
                count = 3**size
                values = np.empty(count, dtype=np.float32)
                for start in range(0, count, batch_size):
                    stop = min(count, start + batch_size)
                    pattern_indices = np.arange(
                        start,
                        stop,
                        dtype=np.uint16,
                    )[:, None]
                    classes = np.full(
                        (len(pattern_indices), 1),
                        class_id,
                        dtype=np.int8,
                    )
                    output, _ = model.forward_pattern(
                        name,
                        pattern_indices,
                        cache=False,
                        class_ids=classes,
                    )
                    values[start:stop] = output[:, 0]
                key = pattern_table_key(phase, name, class_id)
                table, clipped_count = quantize(values, score_scale)
                payload[key] = table
                clipped[key] = clipped_count
                completed += 1
                progress.update(completed)

        for name, specs in PAIR_BRANCHES.items():
            first = np.repeat(np.arange(65, dtype=np.int16), 65)
            second = np.tile(np.arange(65, dtype=np.int16), 65)
            raw = np.column_stack((first, second)).astype(np.float32)
            normalized = np.column_stack(
                tuple(
                    (raw[:, column] - center) / scale
                    for column, (_, center, scale) in enumerate(specs)
                )
            ).astype(np.float32, copy=False)
            values, _ = model.forward_auxiliary(name, normalized, cache=False)
            key = f"phase{phase}_{name}"
            table, clipped_count = quantize(values[:, 0], score_scale)
            payload[key] = table.reshape(65, 65)
            clipped[key] = clipped_count
            completed += 1
            progress.update(completed)

        for name, (minimum, maximum, scale) in SCALAR_BRANCHES.items():
            raw = np.arange(minimum, maximum + 1, dtype=np.float32)
            values, _ = model.forward_auxiliary(
                name,
                (raw / scale)[:, None],
                cache=False,
            )
            key = f"phase{phase}_{name}"
            table, clipped_count = quantize(values[:, 0], score_scale)
            payload[key] = table
            clipped[key] = clipped_count
            completed += 1
            progress.update(completed)

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
        for name, group in PATTERN_GROUPS.items():
            values = data[name][indices]
            for class_id in range(group.class_count or 1):
                columns = np.flatnonzero(
                    np.asarray(group.class_ids) == class_id
                )
                table = tables[pattern_table_key(phase, name, class_id)]
                score += table[values[:, columns]].astype(np.int64).sum(axis=1)
        for name in PAIR_BRANCHES:
            fields = PAIR_BRANCHES[name]
            first = np.clip(data[fields[0][0]][indices], 0, 64)
            second = np.clip(data[fields[1][0]][indices], 0, 64)
            score += tables[f"phase{phase}_{name}"][first, second].astype(
                np.int64
            )
        for name, (minimum, maximum, _) in SCALAR_BRANCHES.items():
            values = np.clip(
                data[name][indices].astype(np.int16),
                minimum,
                maximum,
            )
            score += tables[f"phase{phase}_{name}"][values - minimum].astype(
                np.int64
            )
        prediction = score.astype(np.float32) / float(tables["score_scale"][0])
    expected = labels(data, indices, label_name)[:, 0]
    difference = prediction - expected
    return Metrics(
        float(np.square(difference).mean()),
        float(np.abs(difference).mean()),
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train the six-pattern additive evaluator.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/combined-evaluation-v3"),
        help="input dataset directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/models/pattern-evaluation-v2"),
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
        "--device",
        default="auto",
        help="auto, cpu, cuda, or a CUDA device such as cuda:1",
    )
    parser.add_argument(
        "--amp",
        action="store_true",
        help="use CUDA automatic mixed precision",
    )
    parser.add_argument(
        "--no-progress",
        action="store_true",
        help="disable command-line progress bars",
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
    if args.device != "auto" and args.device != "cpu" and not args.device.startswith(
        "cuda"
    ):
        raise ValueError("device must be auto, cpu, cuda, or cuda:N")
    if args.amp and args.device == "cpu":
        raise ValueError("--amp requires a CUDA device")
    return phase_starts


def resolve_backend(device_name: str) -> tuple[str, Any | None, dict[str, object]]:
    if device_name == "cpu":
        return "numpy", None, {"backend": "numpy", "device": "cpu"}
    if torch is None:
        if device_name.startswith("cuda"):
            raise ValueError(
                "CUDA training requires a CUDA-enabled PyTorch build; "
                "see training/README.md"
            )
        return "numpy", None, {"backend": "numpy", "device": "cpu"}
    if device_name == "auto" and not torch.cuda.is_available():
        return "numpy", None, {
            "backend": "numpy",
            "device": "cpu",
            "torch_version": torch.__version__,
            "cuda_available": False,
        }
    requested = "cuda" if device_name == "auto" else device_name
    if not torch.cuda.is_available():
        raise ValueError("CUDA was requested but torch.cuda.is_available() is false")
    device = torch.device(requested)
    details = {
        "backend": "pytorch",
        "device": str(device),
        "device_name": torch.cuda.get_device_name(device),
        "torch_version": torch.__version__,
        "cuda_version": torch.version.cuda,
        "matmul_precision": "high",
    }
    return "torch", device, details


def main() -> int:
    args = parse_args()
    try:
        phase_starts = validate_args(args)
        backend, device, backend_details = resolve_backend(args.device)
        if args.amp and backend != "torch":
            raise ValueError("--amp was requested but a CUDA backend is unavailable")
        if args.output_dir.exists() and not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {args.output_dir}"
            )
        print(f"training backend: {json.dumps(backend_details)}")
        print(f"load dataset: {args.dataset_dir}")
        train = load_dataset(args.dataset_dir / "train.npz")
        validation = load_dataset(args.dataset_dir / "validation.npz")
        test = load_dataset(args.dataset_dir / "test.npz")
        if args.output_dir.exists():
            shutil.rmtree(args.output_dir)
        args.output_dir.mkdir(parents=True)

        rng = np.random.default_rng(args.seed)
        if backend == "torch":
            torch.manual_seed(args.seed)
            torch.cuda.manual_seed_all(args.seed)
            torch.set_float32_matmul_precision("high")

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
            print(
                f"phase {phase} start: train={len(train_indices)}, "
                f"validation={len(validation_indices)}"
            )
            if backend == "torch":
                model, history = train_phase_torch(
                    phase,
                    train,
                    validation,
                    train_indices,
                    validation_indices,
                    args,
                    rng,
                    device,
                )
            else:
                model, history = train_phase_numpy(
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
            not args.no_progress,
        )
        java_tables_path = args.output_dir / "evaluation-tables.bin"
        java_export = export_java_model(tables_path, java_tables_path)

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
                f"phase {phase} float test",
                not args.no_progress,
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
            "model_format": 2,
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "architecture": {
                "perspective": "side to move",
                "pattern_branches": {
                    name: {
                        "layers": list(_branch_layer_sizes(name)),
                        "instances": group.instances,
                        "classes": list(group.class_names),
                        "linear_output": True,
                    }
                    for name, group in PATTERN_GROUPS.items()
                },
                "pair_branches": {
                    name: list(_branch_layer_sizes(name))
                    for name in PAIR_BRANCHES
                },
                "scalar_branches": {
                    name: list(_branch_layer_sizes(name))
                    for name in SCALAR_BRANCHES
                },
                "activation": "LeakyReLU(alpha=0.01) on hidden layers",
                "combiner": "additive",
                "antisymmetry": "0.5 * (f(own, opponent) - f(opponent, own))",
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
                "backend": backend_details,
                "amp": bool(args.amp and backend == "torch"),
                "selections": selections,
                "history": histories,
            },
            "quantization": {
                "dtype": "int16",
                "score_scale": args.score_scale,
                "clipped_entries": clipped,
                "java_runtime": java_export,
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
    except (OSError, RuntimeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
